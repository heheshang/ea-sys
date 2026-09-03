package com.easysys.api.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.assistant.KbDocumentView;
import com.easysys.api.dto.assistant.KbHit;
import com.easysys.api.dto.assistant.KbSearchView;
import com.easysys.api.entity.KbDocument;
import com.easysys.api.entity.KbDocumentChunk;
import com.easysys.api.mapper.KbDocumentChunkMapper;
import com.easysys.api.mapper.KbDocumentMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业知识库（确定性 RAG）：
 * 上传 = 抽取 → 分块（≈500 字）→ CJK 词元化 → 词频 JSONB 落库；
 * 检索 = GIN 键存在预筛（tokens ?| 查询词）→ Java BM25 打分（k1=1.2, b=0.75, 候选集内 idf）→ topK 引用命中。
 * 全程无向量库、无 LLM，行为可复现、可单测。
 */
@Service
public class KnowledgeBaseService {

    public static final int MAX_CHUNKS = 2_000;
    private static final int CANDIDATE_LIMIT = 5_000;
    private static final int SNIPPET_CHARS = 200;
    private static final double K1 = 1.2d;
    private static final double B = 0.75d;

    private static final TypeReference<Map<String, Integer>> TOKENS_TYPE = new TypeReference<>() {
    };

    private final KbDocumentMapper documentMapper;
    private final KbDocumentChunkMapper chunkMapper;
    private final ObjectMapper json;

    public KnowledgeBaseService(KbDocumentMapper documentMapper, KbDocumentChunkMapper chunkMapper,
            ObjectMapper json) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.json = json;
    }

    /** 上传并解析文档：同步入库；解析/分块失败抛错（不落失败行，前端凭 400 提示）。 */
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentView upload(String filename, long sizeBytes, byte[] bytes) {
        Long tenantId = TenantContext.require();
        String text = TextExtractor.extract(bytes, filename);
        if (text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档内容为空，无法入库");
        }
        List<String> chunks = TextChunker.chunk(text);
        if (chunks.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档内容为空，无法入库");
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文档过大（分块数 " + chunks.size() + " 超过上限 " + MAX_CHUNKS + "），请精简后重试");
        }

        KbDocument doc = new KbDocument();
        doc.setTenantId(tenantId);
        doc.setName(filename);
        String ext = filename.lastIndexOf('.') >= 0 ? filename.substring(filename.lastIndexOf('.') + 1) : "txt";
        doc.setContentType(ext.toLowerCase());
        doc.setSizeBytes(sizeBytes);
        doc.setStatus("ready");
        doc.setChunkCount(chunks.size());
        Instant now = Instant.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setDeleted(false);
        documentMapper.insert(doc);

        int seq = 0;
        for (String chunkText : chunks) {
            KbDocumentChunk chunk = new KbDocumentChunk();
            chunk.setTenantId(tenantId);
            chunk.setDocumentId(doc.getId());
            chunk.setSeq(seq++);
            chunk.setContent(chunkText);
            chunk.setTokens(toJson(CjkTokenizer.counts(chunkText)));
            chunk.setCreatedAt(now);
            chunkMapper.insert(chunk);
        }
        return toView(doc);
    }

    public List<KbDocumentView> listDocuments() {
        Long tenantId = TenantContext.require();
        List<KbDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getTenantId, tenantId)
                .orderByDesc(KbDocument::getCreatedAt));
        return docs.stream().map(this::toView).toList();
    }

    /** 删除文档：逻辑删文档行 + 物理删其全部分块；非本租户文档视为不存在。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        Long tenantId = TenantContext.require();
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null || !doc.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getDocumentId, id));
        documentMapper.deleteById(id);
    }

    /**
     * 检索（租户隔离由调用方保证：工具线程经 {@code withTenant}，接口线程经请求租户上下文）。
     * 候选 = 含任一查询词元的分块（GIN 键存在预筛）→ BM25 打分 → topK 命中。
     */
    public KbSearchView search(String query, int topK) {
        Long tenantId = TenantContext.require();
        List<String> qKeys = CjkTokenizer.keys(query);
        if (qKeys.isEmpty()) {
            return new KbSearchView(query, List.of(), "未识别到检索词，请换个问法试试");
        }
        List<KbDocumentChunk> candidates = chunkMapper.selectList(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getTenantId, tenantId)
                // jsonb_exists_any(tokens, text[]) 等价于 tokens ?| ARRAY[...]（GIN jsonb_ops
                // 支持 ?| 运算符，但 MyBatis 会把 SQL 文本里的 ? 当作 JDBC 占位符、无法透传
                // '?|'，故用函数式等价物；分块规模（租户内 ≤2000）下顺序扫描可接受，后续
                // 如需复用 GIN 可将检索下沉到原生 SQL 层。
                .apply("jsonb_exists_any(tokens, ARRAY[" + quoted(qKeys) + "]::text[])")
                .last("LIMIT " + CANDIDATE_LIMIT));
        if (candidates.isEmpty()) {
            return new KbSearchView(query, List.of(),
                    "知识库中暂未找到与「" + query + "」相关的内容，可先上传相关文档（支持 txt/md/csv/xlsx/docx/pdf）后再问");
        }

        Map<Long, Map<String, Integer>> tfs = new HashMap<>();
        Map<Long, Integer> lens = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (KbDocumentChunk c : candidates) {
            Map<String, Integer> tf = parseTokens(c.getTokens());
            tfs.put(c.getId(), tf);
            lens.put(c.getId(), c.getContent().length());
            totalLen += c.getContent().length();
            for (String key : tf.keySet()) {
                df.merge(key, 1, Integer::sum);
            }
        }
        int n = candidates.size();
        double avgdl = n == 0 ? 0d : (double) totalLen / n;

        List<Scored> scored = new ArrayList<>();
        for (KbDocumentChunk c : candidates) {
            Map<String, Integer> tf = tfs.get(c.getId());
            double dl = lens.get(c.getId());
            double score = 0d;
            for (String key : qKeys) {
                int t = tf.getOrDefault(key, 0);
                if (t <= 0) {
                    continue;
                }
                double idf = Math.log((n - df.getOrDefault(key, 0) + 0.5d) / (df.getOrDefault(key, 0) + 0.5d) + 1d);
                double tfNorm = t * (K1 + 1d) / (t + K1 * (1d - B + B * dl / avgdl));
                score += idf * tfNorm;
            }
            if (score > 0d) {
                scored.add(new Scored(c, score));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        List<KbHit> hits = new ArrayList<>();
        for (Scored s : scored) {
            if (hits.size() >= topK) {
                break;
            }
            KbDocumentChunk c = s.chunk;
            hits.add(new KbHit(c.getDocumentId(), null, c.getSeq(), snippet(c.getContent()), s.score));
        }
        if (hits.isEmpty()) {
            return new KbSearchView(query, List.of(),
                    "知识库中暂未找到与「" + query + "」相关的内容，可先上传相关文档（支持 txt/md/csv/xlsx/docx/pdf）后再问");
        }
        // 补齐文档名（批量取一次，逻辑删除过滤自动生效）
        List<Long> docIds = hits.stream().map(KbHit::documentId).distinct().toList();
        Map<Long, String> names = documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, KbDocument::getName, (a, b) -> a));
        List<KbHit> named = hits.stream()
                .map(h -> new KbHit(h.documentId(), names.getOrDefault(h.documentId(), "未知文档"),
                        h.seq(), h.content(), h.score()))
                .toList();
        return new KbSearchView(query, named, null);
    }

    private String toJson(Map<String, Integer> counts) {
        try {
            return json.writeValueAsString(counts);
        } catch (Exception e) {
            throw new IllegalStateException("词频序列化失败", e);
        }
    }

    private Map<String, Integer> parseTokens(String tokens) {
        if (tokens == null || tokens.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(tokens, TOKENS_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String quoted(List<String> keys) {
        return keys.stream().map(k -> "'" + k + "'").collect(Collectors.joining(","));
    }

    private static String snippet(String content) {
        String trimmed = content.trim();
        return trimmed.length() <= SNIPPET_CHARS ? trimmed : trimmed.substring(0, SNIPPET_CHARS) + "…";
    }

    private KbDocumentView toView(KbDocument d) {
        return new KbDocumentView(d.getId(), d.getName(), d.getContentType(), d.getSizeBytes(),
                d.getStatus(), d.getError(), d.getChunkCount(), d.getCreatedAt());
    }

    private record Scored(KbDocumentChunk chunk, double score) {
    }
}