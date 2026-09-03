package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.assistant.KnowledgeBaseService;
import com.easysys.api.dto.assistant.KbSearchView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 助手工具：search_kb —— 企业知识库检索（确定性 RAG：词频预筛 + BM25 打分，
 * 输出 {@code {query, hits:[{documentId, documentName, seq, content, score}], note}}，
 * 策略器据此做引用式回答，控制器同步产出命中卡片）。
 */
@Component
public class AssistantSearchKbTool extends AssistantToolBase {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper json;

    public AssistantSearchKbTool(KnowledgeBaseService knowledgeBaseService, ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_SEARCH_KB)
                .description("在租户企业知识库中检索问题答案（引用文档原文段落），知识库由上传的 txt/md/csv/xlsx/docx/pdf 文档构建")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string", "description", "用户问题原文")),
                        "required", java.util.List.of("query")))
                .readOnly(true)
                .concurrencySafe(true));
        this.knowledgeBaseService = knowledgeBaseService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读检索"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Object query = param.getInput() == null ? null : param.getInput().get("query");
            if (query == null || query.toString().isBlank()) {
                throw new IllegalArgumentException("search_kb 缺少 query 参数");
            }
            String q = query.toString().trim();
            KbSearchView view = knowledgeBaseService.search(q, 3);
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, AssistantPolicy.TOOL_SEARCH_KB,
                    TextBlock.builder().text(json.writeValueAsString(view)).build());
        })).onErrorResume(e -> errorResult(param, AssistantPolicy.TOOL_SEARCH_KB, json, e));
    }
}