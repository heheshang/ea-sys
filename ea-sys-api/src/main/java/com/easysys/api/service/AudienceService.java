package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.easysys.api.dto.audience.AudienceRequest;
import com.easysys.api.dto.audience.AudienceRow;
import com.easysys.api.dto.audience.AudienceResponse;
import com.easysys.api.dto.audience.MemberView;
import com.easysys.api.dto.audience.SnapshotResponse;
import com.easysys.api.entity.Audience;
import com.easysys.api.entity.AudienceSnapshot;
import com.easysys.api.entity.Contact;
import com.easysys.api.mapper.AudienceMapper;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.AudienceSnapshotMemberMapper;
import com.easysys.api.mapper.ContactMapper;
import com.easysys.api.rule.RuleCompiler;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.common.web.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 人群：规则维护 + 圈选执行 + 快照冻结。
 * 圈选 = DSL 编译为参数化 WHERE，DB 内过滤；结果冻结到 audience_snapshot_member（filter_version 追溯）。
 * M1 同步执行（building → ready 同一事务）；M2 引入队列后拆异步。
 */
@Service
public class AudienceService {

    private static final int MEMBER_BATCH = 500;

    private final AudienceMapper audienceMapper;
    private final AudienceSnapshotMapper snapshotMapper;
    private final AudienceSnapshotMemberMapper memberMapper;
    private final ContactMapper contactMapper;
    private final ObjectMapper json;

    public AudienceService(AudienceMapper audienceMapper, AudienceSnapshotMapper snapshotMapper,
                           AudienceSnapshotMemberMapper memberMapper, ContactMapper contactMapper,
                           ObjectMapper json) {
        this.audienceMapper = audienceMapper;
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
        this.contactMapper = contactMapper;
        this.json = json;
    }

    @Transactional
    public AudienceResponse create(AudienceRequest req, String operator) {
        Long tenantId = TenantContext.require();
        RuleCompiler.compile(req.rule(), tenantId); // 校验；非法即抛 400
        Audience a = new Audience();
        a.setTenantId(TenantContext.require());
        a.setName(req.name().trim());
        a.setRule(writeJson(req.rule()));
        a.setVersion(1);
        a.setStatus("published"); // M1 无草稿发布流：创建即可直接圈选
        a.setCreatedBy(operator);
        audienceMapper.insert(a);
        return toResponse(a, latestSnapshot(a.getId()));
    }

    @Transactional
    public AudienceResponse update(Long id, AudienceRequest req) {
        Audience a = requireAudience(id);
        Long tenantId = TenantContext.require();
        RuleCompiler.compile(req.rule(), tenantId); // 校验
        a.setName(req.name().trim());
        a.setRule(writeJson(req.rule()));
        a.setVersion(a.getVersion() == null ? 2 : a.getVersion() + 1);
        a.setUpdatedAt(Instant.now());
        audienceMapper.updateById(a);
        return toResponse(a, latestSnapshot(id));
    }

    public PageResponse<AudienceResponse> list(long page, long size) {
        IPage<AudienceRow> p = audienceMapper.selectAudiencePage(
                new Page<>(page, size), TenantContext.require());
        List<AudienceResponse> records = p.getRecords().stream().map(AudienceService::toResponse).toList();
        return PageResponse.of(records, p.getTotal(), page, size);
    }

    public AudienceResponse get(Long id) {
        Audience a = requireAudience(id);
        return toResponse(a, latestSnapshot(id));
    }

    @Transactional
    public void delete(Long id) {
        requireAudience(id);
        audienceMapper.deleteById(id);
    }

    /**
     * 触发圈选：DSL 编译 → DB 过滤出 contact id → 分批冻结到快照成员 → 置 ready。
     */
    @Transactional
    public SnapshotResponse circle(Long audienceId) {
        Long tenantId = TenantContext.require();
        Audience a = requireAudience(audienceId);
        RuleCompiler.SqlSegment seg = RuleCompiler.compile(parseRule(a.getRule()), tenantId);

        AudienceSnapshot snap = new AudienceSnapshot();
        snap.setTenantId(tenantId);
        snap.setAudienceId(audienceId);
        snap.setStatus("building");
        snap.setMemberCount(0);
        snap.setFilterVersion(a.getVersion());
        snapshotMapper.insert(snap);

        // MP QueryWrapper.apply 用 {0}..{n} 占位符：把编译器输出的裸 ? 按参数顺序渲染后绑定
        // 主表 tenant_id / deleted 由 MP 租户与逻辑删除插件注入；子查询租户参数已编入 seg.params() 末位
        StringBuilder rendered = new StringBuilder();
        int ph = 0;
        for (int i = 0; i < seg.sql().length(); i++) {
            char c = seg.sql().charAt(i);
            if (c == '?') {
                rendered.append('{').append(ph++).append('}');
            } else {
                rendered.append(c);
            }
        }
        QueryWrapper<Contact> w = new QueryWrapper<>();
        w.select("id").apply("(" + rendered + ")", seg.params().toArray());
        List<Long> ids = contactMapper.selectList(w).stream().map(Contact::getId).toList();

        for (int i = 0; i < ids.size(); i += MEMBER_BATCH) {
            memberMapper.insertBatch(snap.getId(), ids.subList(i, Math.min(i + MEMBER_BATCH, ids.size())));
        }

        snap.setMemberCount(ids.size());
        snap.setStatus("ready");
        snap.setUpdatedAt(Instant.now());
        snapshotMapper.updateById(snap);
        return SnapshotResponse.of(snap);
    }

    public PageResponse<SnapshotResponse> snapshots(Long audienceId, long page, long size) {
        requireAudience(audienceId);
        IPage<AudienceSnapshot> p = snapshotMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AudienceSnapshot>()
                        .eq(AudienceSnapshot::getAudienceId, audienceId)
                        .orderByDesc(AudienceSnapshot::getExecutedAt));
        List<SnapshotResponse> records = p.getRecords().stream().map(SnapshotResponse::of).toList();
        return PageResponse.of(records, p.getTotal(), page, size);
    }

    public PageResponse<MemberView> members(Long snapshotId, long page, long size) {
        AudienceSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "快照不存在: " + snapshotId);
        }
        IPage<MemberView> p = memberMapper.selectMembers(
                new Page<>(page, size), snapshotId, snap.getTenantId());
        return PageResponse.of(p.getRecords(), p.getTotal(), page, size);
    }

    private Audience requireAudience(Long id) {
        Audience a = audienceMapper.selectById(id);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "人群不存在: " + id);
        }
        return a;
    }

    private AudienceResponse.LatestSnapshot latestSnapshot(Long audienceId) {
        AudienceSnapshot s = snapshotMapper.selectOne(
                new LambdaQueryWrapper<AudienceSnapshot>()
                        .eq(AudienceSnapshot::getAudienceId, audienceId)
                        .orderByDesc(AudienceSnapshot::getExecutedAt)
                        .last("limit 1"));
        if (s == null) {
            return null;
        }
        return new AudienceResponse.LatestSnapshot(s.getId(), s.getStatus(), s.getMemberCount(), s.getExecutedAt());
    }

    private JsonNode parseRule(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "规则数据损坏");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则不是合法 JSON");
        }
    }

    private static AudienceResponse toResponse(Audience a, AudienceResponse.LatestSnapshot latest) {
        return new AudienceResponse(a.getId(), a.getName(), a.getRule(), a.getVersion(), a.getStatus(),
                a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt(), latest);
    }

    private static AudienceResponse toResponse(AudienceRow r) {
        AudienceResponse.LatestSnapshot latest = r.latestSnapshotId() == null ? null
                : new AudienceResponse.LatestSnapshot(r.latestSnapshotId(), r.latestSnapshotStatus(),
                r.latestSnapshotMemberCount(), r.latestSnapshotExecutedAt());
        return new AudienceResponse(r.id(), r.name(), r.rule(), r.version(), r.status(),
                r.createdBy(), r.createdAt(), r.updatedAt(), latest);
    }
}