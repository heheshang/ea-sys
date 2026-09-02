package com.easysys.api.service;

import com.easysys.agent.AgentCall;
import com.easysys.agent.AgentExecutor;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.AgentRunConfig;
import com.easysys.agent.DeterministicChurnPlanner;
import com.easysys.api.dto.agent.ChurnScanRequest;
import com.easysys.api.dto.agent.ChurnScanView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.AudienceSnapshot;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.AudienceSnapshotMemberMapper;
import com.easysys.api.mapper.ContactAttributeMapper;
import com.easysys.api.mapper.EventMapper;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流失预警：对快照成员按「N 天未活跃 = HIGH」规则批量评估（CHURN Agent，确定性降级即主实现）。
 * 活跃信号 = 行为事件（event 表）；最近事件距今 &gt; 阈值 → HIGH。结果回写 contact_attribute.churn_risk（jsonb 字符串），
 * 全程走 AgentExecutor（schema 校验 + 一次性审计），与分层/路由同一治理路径。
 */
@Service
public class ChurnService {

    private static final int BATCH = 500;
    private static final String CHURN_RISK_KEY = "churn_risk";

    private final AudienceSnapshotMapper snapshotMapper;
    private final AudienceSnapshotMemberMapper memberMapper;
    private final EventMapper eventMapper;
    private final ContactAttributeMapper attributeMapper;
    private final AgentAuditMapper auditLogMapper;
    private final LayerTagger layerTagger;
    private final ObjectMapper json;

    public ChurnService(AudienceSnapshotMapper snapshotMapper, AudienceSnapshotMemberMapper memberMapper,
                        EventMapper eventMapper, ContactAttributeMapper attributeMapper,
                        AgentAuditMapper auditLogMapper, LayerTagger layerTagger, ObjectMapper json) {
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
        this.eventMapper = eventMapper;
        this.attributeMapper = attributeMapper;
        this.auditLogMapper = auditLogMapper;
        this.layerTagger = layerTagger;
        this.json = json;
    }

    /** 批量扫描快照成员流失风险并回写标记（churn_scan）。 */
    @Transactional
    public ChurnScanView scan(ChurnScanRequest req, String operator) {
        Long tenantId = TenantContext.require();
        AudienceSnapshot snapshot = snapshotMapper.selectById(req.audienceSnapshotId());
        if (snapshot == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "快照不存在: " + req.audienceSnapshotId());
        }
        int threshold = req.inactiveDays() == null ? 30 : req.inactiveDays();

        List<Long> contactIds = memberMapper.selectContactIds(snapshot.getId());
        Map<Long, Instant> lastActive = lastActiveAt(tenantId, contactIds);
        ArrayNode contacts = json.createArrayNode();
        for (Long cid : contactIds) {
            ObjectNode c = contacts.addObject();
            c.put("contact_id", cid);
            Instant last = lastActive.get(cid);
            if (last == null) {
                c.putNull("inactive_days");
            } else {
                long days = Duration.between(last, Instant.now()).toDays();
                c.put("inactive_days", Math.max(0, days));
            }
        }

        ObjectNode input = json.createObjectNode();
        input.set("contacts", contacts);
        input.put("threshold_days", threshold);

        DeterministicChurnPlanner planner = new DeterministicChurnPlanner();
        AgentOutcome outcome = AgentExecutor.run(planner, planner, "churn_scan", input, AgentRunConfig.defaults());
        if (outcome.output() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "流失风险评估失败（确定性兜底也失效）: " + outcome.reason());
        }

        persistAudit(tenantId, outcome, operator);

        JsonNode summary = outcome.output().path("summary");
        int scanned = summary.path("scanned").asInt(0);
        int high = summary.path("HIGH").asInt(0);
        int medium = summary.path("MEDIUM").asInt(0);
        int low = summary.path("LOW").asInt(0);

        // 回写 churn_risk（jsonb 字符串，规则/分层可引用 attribute.churn_risk）
        Map<Long, String> tiers = new HashMap<>();
        for (JsonNode r : outcome.output().path("results")) {
            tiers.put(r.path("contact_id").asLong(), r.path("tier").asText());
        }
        layerTagger.markChurnRisk(tenantId, tiers);

        return new ChurnScanView(snapshot.getId(), threshold, scanned, high, medium, low,
                (int) countChurnAttr(tenantId, contactIds));
    }

    /** contactId → 最近事件时间；无事件 → 缺省（视为从未活跃）。 */
    private Map<Long, Instant> lastActiveAt(Long tenantId, List<Long> contactIds) {
        Map<Long, Instant> map = new HashMap<>();
        for (int i = 0; i < contactIds.size(); i += BATCH) {
            List<Long> batch = new ArrayList<>(contactIds.subList(i, Math.min(i + BATCH, contactIds.size())));
            for (Map<String, Object> row : eventMapper.selectLastActive(tenantId, batch)) {
                map.put(((Number) row.get("contact_id")).longValue(),
                        ((Timestamp) row.get("last_at")).toInstant());
            }
        }
        return map;
    }

    private long countChurnAttr(Long tenantId, List<Long> contactIds) {
        if (contactIds.isEmpty()) {
            return 0;
        }
        return attributeMapper.countByKeyAndContacts(tenantId, CHURN_RISK_KEY, contactIds);
    }

    /** audit_log 持久化（与策略审计同一结构：agent_type=CHURN，schema_valid 来自执行结果）。 */
    private void persistAudit(Long tenantId, AgentOutcome outcome, String operator) {
        AgentCall a = outcome.audit();
        AgentAudit log = new AgentAudit();
        log.setTenantId(tenantId);
        log.setAgentType(a.agentType().name());
        log.setAction(a.action());
        log.setStatus(outcome.status());
        log.setReason(outcome.reason());
        try {
            log.setInputSummary(json.writeValueAsString(a.inputSummary()));
            log.setOutput(json.writeValueAsString(a.output()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "审计序列化失败: " + e.getMessage());
        }
        log.setSchemaValid(!"ERROR".equals(outcome.status())
                && (outcome.reason() == null || !outcome.reason().contains("invalid")));
        log.setStrategyVersion("rule");
        if (a.confidence() != null) {
            log.setConfidence(java.math.BigDecimal.valueOf(a.confidence()));
        }
        log.setModel(a.model());
        log.setDurationMs(a.durationMs());
        log.setOperator(operator);
        log.setCreatedAt(Instant.now());
        auditLogMapper.insert(log);
    }
}