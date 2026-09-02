package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.agent.AgentOutcome;
import com.easysys.agent.DeterministicLayerPlanner;
import com.easysys.agent.AgentExecutor;
import com.easysys.agent.AgentRunConfig;
import com.easysys.api.dto.agent.StrategyRequest;
import com.easysys.api.dto.agent.StrategyView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.LayerStrategy;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.LayerStrategyMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 分层策略（LAYER agent）：
 * - 生成 = AgentExecutor.run(确定性规划器, 同规划器兜底) → schema 校验 + 置信度闸门 + 审计
 * - 版本/发布闸门：draft → published；同租户可多份历史发布，生效取最近 published_at
 * - 审计持久化到 audit_log（strategy_generate 动作）
 */
@Service
public class StrategyService {

    private static final long ACTIVE_LIMIT = 1;

    private final LayerStrategyMapper strategyMapper;
    private final AgentAuditMapper auditMapper;
    private final ObjectMapper json;

    public StrategyService(LayerStrategyMapper strategyMapper, AgentAuditMapper auditMapper, ObjectMapper json) {
        this.strategyMapper = strategyMapper;
        this.auditMapper = auditMapper;
        this.json = json;
    }

    /** 生成并落库 draft 策略（LLM 接入前为确定性规则提供方）。 */
    @Transactional
    public StrategyView generate(StrategyRequest req, String operator) {
        Long tenantId = TenantContext.require();
        ObjectNode input = json.createObjectNode();
        boolean autoVersion = req.strategyVersion() == null || req.strategyVersion().isBlank();
        String version = autoVersion ? epochVersion() : req.strategyVersion().trim();
        input.put("strategy_version", version);
        ArrayNode order = input.putArray("route_order");
        if (req.routeOrder() != null) {
            for (String c : req.routeOrder()) {
                if (c.equals("sms") || c.equals("email")) {
                    order.add(c);
                }
            }
        }
        if (order.isEmpty()) {
            order.add("sms").add("email");
        }

        DeterministicLayerPlanner planner = new DeterministicLayerPlanner();
        AgentOutcome outcome = AgentExecutor.run(planner, planner, "strategy_generate", input, AgentRunConfig.defaults());
        if (outcome.output() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "策略生成失败（确定性兜底也失效）: " + outcome.reason());
        }
        persistAudit(tenantId, outcome, operator);

        LayerStrategy s = new LayerStrategy();
        try {
            s.setTenantId(tenantId);
            s.setName(req.name().trim());
            s.setDimensions(json.writeValueAsString(outcome.output().get("dimensions")));
            s.setRouteOrder(json.writeValueAsString(order));
            s.setStrategy(json.writeValueAsString(outcome.output()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略文档序列化失败: " + e.getMessage());
        }
        s.setSource(outcome.output().path("source").asText("deterministic"));
        s.setStatus("draft");
        s.setStrategyVersion(version);
        s.setConfidence(java.math.BigDecimal.valueOf(outcome.audit().confidence()));
        s.setCreatedBy(operator);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        strategyMapper.insert(s);
        return toView(s);
    }

    public List<StrategyView> list() {
        Long tenantId = TenantContext.require();
        return strategyMapper.selectList(new LambdaQueryWrapper<LayerStrategy>()
                        .eq(LayerStrategy::getTenantId, tenantId)
                        .orderByDesc(LayerStrategy::getCreatedAt))
                .stream().map(this::toView).toList();
    }

    public StrategyView get(Long id) {
        return toView(require(id));
    }

    /** 发布闸门：draft → published（幂等：重复发布直接返回）。 */
    @Transactional
    public StrategyView publish(Long id) {
        LayerStrategy s = require(id);
        if (!"draft".equals(s.getStatus()) && !"published".equals(s.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略状态不允许发布: " + s.getStatus());
        }
        if (!"published".equals(s.getStatus())) {
            s.setStatus("published");
            s.setPublishedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            strategyMapper.updateById(s);
        }
        return toView(s);
    }

    @Transactional
    public void delete(Long id) {
        LayerStrategy s = require(id);
        strategyMapper.deleteById(s.getId());
    }

    /** 生效策略视图：无 → null（调用方用默认分层兜底）。 */
    public StrategyView getActive() {
        LayerStrategy s = activeStrategy();
        return s == null ? null : toView(s);
    }

    /** 生效策略：最近发布的 published 行；无 → null（调用方用默认分层兜底）。 */
    public LayerStrategy activeStrategy() {
        Long tenantId = TenantContext.require();
        return strategyMapper.selectList(new LambdaQueryWrapper<LayerStrategy>()
                        .eq(LayerStrategy::getTenantId, tenantId)
                        .eq(LayerStrategy::getStatus, "published")
                        .orderByDesc(LayerStrategy::getPublishedAt)
                        .last("LIMIT " + ACTIVE_LIMIT))
                .stream().findFirst().orElse(null);
    }

    /** audit_log 持久化（AgentOutcome → AgentAudit）。 */
    public void persistAudit(Long tenantId, AgentOutcome outcome, String operator) {
        AgentAudit a = new AgentAudit();
        a.setTenantId(tenantId);
        a.setAgentType(outcome.audit().agentType().name());
        a.setAction(outcome.audit().action());
        a.setStatus(outcome.status());
        a.setReason(outcome.reason());
        a.setInputSummary(writeOrNull(outcome.audit().inputSummary()));
        a.setOutput(writeOrNull(outcome.audit().output()));
        // schema 校验结果：主提供方输出未过校验 → fallback，fallback 输出仍校验；任一 invalid → false
        a.setSchemaValid(!"ERROR".equals(outcome.status())
                && (outcome.reason() == null || !outcome.reason().contains("invalid")));
        a.setStrategyVersion(outcome.audit().strategyVersion());
        a.setConfidence(outcome.audit().confidence() == null
                ? null : java.math.BigDecimal.valueOf(outcome.audit().confidence()));
        a.setModel(outcome.audit().model());
        a.setTokens(outcome.audit().tokens());
        a.setDurationMs(outcome.audit().durationMs());
        a.setOperator(operator);
        a.setCreatedAt(Instant.now());
        auditMapper.insert(a);
    }

    private String writeOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(n);
        } catch (Exception e) {
            return null;
        }
    }

    private LayerStrategy require(Long id) {
        Long tenantId = TenantContext.require();
        LayerStrategy s = strategyMapper.selectById(id);
        if (s == null || !tenantId.equals(s.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        }
        return s;
    }

    /** 未指定版本时自动生成：v+epoch 毫秒（唯一约束 uq_layer_strategy_tenant_version 防碰撞）。 */
    private String epochVersion() {
        return "v" + System.currentTimeMillis();
    }

    private StrategyView toView(LayerStrategy s) {
        return new StrategyView(s.getId(), s.getName(), parse(s.getDimensions()), parse(s.getRouteOrder()),
                parse(s.getStrategy()), s.getSource(), s.getStatus(), s.getStrategyVersion(),
                s.getConfidence(), s.getCreatedBy(), s.getCreatedAt(), s.getPublishedAt());
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}