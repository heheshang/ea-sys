package com.easysys.api.service.plan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.plan.PlanDocument;
import com.easysys.api.dto.plan.PlanValidationView;
import com.easysys.api.entity.ChannelConfig;
import com.easysys.api.mapper.ChannelConfigMapper;
import com.easysys.channel.ChannelAdapter;
import com.easysys.engine.entity.Template;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.TemplateMapper;
import com.easysys.engine.mapper.WorkflowEdgeMapper;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.mapper.WorkflowNodeMapper;
import com.easysys.engine.model.TriggerConfig;
import com.easysys.engine.model.TriggerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划一致性校验器：结构化计划 vs 工作流最新可用配置（draft 优先，其次最新 published）。
 * 8 个维度产出分级结论 — 任一 BLOCKED → BLOCKED；否则任一 WARNINGS → WARNINGS；否则 PASSED。
 * <ul>
 *   <li>trigger  触发方式/定时/事件比对（方式不同 → BLOCKED；cron/事件名不一致 → WARNINGS）</li>
 *   <li>channel  通道接入与启用（未接入 → BLOCKED；未启用 → BLOCKED；无凭据 → WARNINGS）</li>
 *   <li>route_order 触达顺序 vs ACTION 链（不一致 → WARNINGS）</li>
 *   <li>timing   延迟档位 vs DELAY 节点（计划要求延迟但无 DELAY → WARNINGS，反之亦然）</li>
 *   <li>template 消息模板存在性（缺失 → BLOCKED；通道不匹配 → WARNINGS）</li>
 *   <li>frequency 单用户频率上限（&gt;1 触碰 FrequencyGuard 24h 窗口 → BLOCKED）</li>
 *   <li>audience 人群规则（确定性模式下 PASSED + 语义比对待 LLM 里程碑提示）</li>
 *   <li>copy_notes 文案要求（同上）</li>
 * </ul>
 */
@Component
public class PlanConsistencyValidator {

    private static final String BLOCKED = "BLOCKED";
    private static final String WARNINGS = "WARNINGS";
    private static final String PASSED = "PASSED";

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final TemplateMapper templateMapper;
    private final ChannelConfigMapper channelConfigMapper;
    private final Set<String> registeredChannels;
    private final ObjectMapper json;

    public PlanConsistencyValidator(WorkflowMapper workflowMapper,
                                    WorkflowNodeMapper nodeMapper,
                                    WorkflowEdgeMapper edgeMapper,
                                    TemplateMapper templateMapper,
                                    ChannelConfigMapper channelConfigMapper,
                                    List<ChannelAdapter> adapters,
                                    ObjectMapper json) {
        this.workflowMapper = workflowMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.templateMapper = templateMapper;
        this.channelConfigMapper = channelConfigMapper;
        this.registeredChannels = adapters.stream().map(ChannelAdapter::channel).collect(Collectors.toSet());
        this.json = json;
    }

    /** 校验入口：取最新可用配置行，逐维度比对，返回分级视图。 */
    public PlanValidationView validate(Long workflowId, PlanDocument doc, Long tenantId) {
        Workflow wf = editableRow(workflowId);
        if (wf == null) {
            throw new com.easysys.common.web.BizException(
                    com.easysys.common.web.ErrorCode.NOT_FOUND, "工作流不存在: " + workflowId);
        }
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, wf.getRefId())
                .eq(WorkflowNode::getVersion, wf.getVersion()));
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWorkflowId, wf.getRefId())
                .eq(WorkflowEdge::getVersion, wf.getVersion()));

        List<PlanValidationView.Dimension> dimensions = new ArrayList<>();
        dimensions.add(triggerDimension(doc.overview(), nodes));
        dimensions.add(channelDimension(doc.routes(), tenantId));
        dimensions.add(routeOrderDimension(doc.routes(), nodes, edges));
        dimensions.add(timingDimension(doc.routes(), nodes));
        dimensions.add(templateDimension(doc.routes(), tenantId));
        dimensions.add(frequencyDimension(doc.routes()));
        dimensions.add(audienceDimension(doc.audienceRules()));
        dimensions.add(copyNotesDimension(doc.copyNotes(), tenantId));

        int conflicts = (int) dimensions.stream().filter(d -> BLOCKED.equals(d.level())).count();
        int warnings = (int) dimensions.stream().filter(d -> WARNINGS.equals(d.level())).count();
        int passed = dimensions.size() - conflicts - warnings;
        String decision = conflicts > 0 ? BLOCKED : (warnings > 0 ? WARNINGS : PASSED);
        String planSummary = summarize(doc);
        return new PlanValidationView(null, workflowId, doc.overview().planName(), null, null,
                decision, planSummary, dimensions, new PlanValidationView.Summary(conflicts, warnings, passed),
                null, null);
    }

    // ---------- 维度判定 ----------

    private PlanValidationView.Dimension triggerDimension(PlanDocument.PlanOverview plan, List<WorkflowNode> nodes) {
        JsonNode cfg = triggerConfigNode(nodes);
        TriggerConfig wfTrigger = TriggerConfig.of(cfg);
        if (wfTrigger == null) {
            return new PlanValidationView.Dimension("trigger", PASSED,
                    plan.triggerType() + " 触发", "无触发配置（手动）",
                    "工作流为手动/API 触发，无定时或事件配置", "无");
        }
        String wfType = wfTrigger.triggerType();
        String planType = normalizePlanTrigger(plan.triggerType());

        // 方式映射：计划 TIMED ↔ 工作流 SCHEDULED；EVENT ↔ EVENT；MANUAL ↔ MANUAL/API
        boolean compatible = switch (planType) {
            case "TIMED" -> TriggerType.SCHEDULED.name().equals(wfType);
            case "EVENT" -> TriggerType.EVENT.name().equals(wfType);
            default -> TriggerType.MANUAL.name().equals(wfType) || TriggerType.API.name().equals(wfType);
        };
        if (!compatible) {
            return new PlanValidationView.Dimension("trigger", BLOCKED,
                    "计划触发方式 " + planType, "工作流触发方式 " + wfType,
                    "触发方式不一致，计划无法按预期触发", "调整计划触发方式或工作流 TRIGGER 配置");
        }
        if ("TIMED".equals(planType)) {
            String planCron = plan.triggerTime() == null ? "" : plan.triggerTime().trim();
            String wfCron = wfTrigger.cron() == null ? "" : wfTrigger.cron().trim();
            if (!planCron.equals(wfCron)) {
                return new PlanValidationView.Dimension("trigger", WARNINGS,
                        "计划定时 " + planCron, "工作流定时 " + wfCron,
                        "定时 cron 不一致，实际执行按工作流配置", "统一 cron 或确认计划意图");
            }
        }
        if ("EVENT".equals(planType)) {
            String planEvent = plan.eventName() == null ? "" : plan.eventName().trim();
            String wfEvent = wfTrigger.eventName() == null ? "" : wfTrigger.eventName().trim();
            if (!planEvent.equals(wfEvent)) {
                return new PlanValidationView.Dimension("trigger", WARNINGS,
                        "计划事件 " + planEvent, "工作流事件 " + wfEvent,
                        "事件名不一致，实际执行按工作流配置", "统一事件名或确认计划意图");
            }
        }
        return new PlanValidationView.Dimension("trigger", PASSED,
                plan.triggerType() + " 触发", wfType + " 触发",
                "触发方式一致", "无");
    }

    private PlanValidationView.Dimension channelDimension(List<PlanDocument.PlanRouteRow> routes, Long tenantId) {
        List<String> channels = routes.stream().map(PlanDocument.PlanRouteRow::channel)
                .map(this::normalizeChannel).distinct().toList();
        List<String> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String ch : channels) {
            if (!registeredChannels.contains(ch)) {
                conflicts.add("通道 " + ch + " 未接入（系统无此通道适配器）");
                continue;
            }
            List<ChannelConfig> cfgs = channelConfigMapper.selectList(
                    new LambdaQueryWrapper<ChannelConfig>()
                            .eq(ChannelConfig::getChannel, ch)
                            .eq(ChannelConfig::getEnabled, true));
            boolean enabled = cfgs.stream().anyMatch(c -> Boolean.TRUE.equals(c.getEnabled()));
            if (enabled) {
                continue;
            }
            List<ChannelConfig> any = channelConfigMapper.selectList(
                    new LambdaQueryWrapper<ChannelConfig>().eq(ChannelConfig::getChannel, ch));
            if (any.isEmpty()) {
                warnings.add("通道 " + ch + " 未配置凭据，执行将降级 console");
            } else {
                conflicts.add("通道 " + ch + " 已配置但未启用");
            }
        }
        if (!conflicts.isEmpty()) {
            return new PlanValidationView.Dimension("channel", BLOCKED,
                    String.join("、", channels), "系统通道配置",
                    String.join("；", conflicts), "配置并启用对应通道凭据后重新导入");
        }
        if (!warnings.isEmpty()) {
            return new PlanValidationView.Dimension("channel", WARNINGS,
                    String.join("、", channels), "系统通道配置",
                    String.join("；", warnings), "通道将按 console 模式降级执行");
        }
        return new PlanValidationView.Dimension("channel", PASSED,
                String.join("、", channels), "系统通道配置", "计划通道均已接入且启用", "无");
    }

    private PlanValidationView.Dimension routeOrderDimension(List<PlanDocument.PlanRouteRow> routes,
                                                             List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        List<String> planOrder = routes.stream()
                .sorted(java.util.Comparator.comparing(PlanDocument.PlanRouteRow::sequence))
                .map(r -> normalizeChannel(r.channel()))
                .toList();
        List<String> dagOrder = actionChannelChain(nodes, edges);
        if (!planOrder.equals(dagOrder)) {
            return new PlanValidationView.Dimension("route_order", WARNINGS,
                    "计划顺序 " + planOrder, "工作流 ACTION 链 " + dagOrder,
                    "触达顺序与工作流 ACTION 链不一致，实际执行按工作流拓扑", "调整计划顺序或工作流节点连线");
        }
        return new PlanValidationView.Dimension("route_order", PASSED,
                "计划顺序 " + planOrder, "工作流 ACTION 链 " + dagOrder,
                "触达顺序一致", "无");
    }

    private PlanValidationView.Dimension timingDimension(List<PlanDocument.PlanRouteRow> routes,
                                                         List<WorkflowNode> nodes) {
        boolean planHasDelay = routes.stream().anyMatch(r -> parseDelayDays(r.timing()) > 0);
        boolean dagHasDelay = nodes.stream().anyMatch(n -> "DELAY".equalsIgnoreCase(n.getType()));
        if (planHasDelay && !dagHasDelay) {
            return new PlanValidationView.Dimension("timing", WARNINGS,
                    "计划含 D+ 延迟档", "工作流无 DELAY 节点",
                    "计划要求相对延迟，但工作流节点间无延时，实际执行将连续触达", "在工作流中补充 DELAY 节点");
        }
        if (!planHasDelay && dagHasDelay) {
            return new PlanValidationView.Dimension("timing", WARNINGS,
                    "计划全为 D+0 即时", "工作流含 DELAY 节点",
                    "工作流在节点间有延时，与计划即时触达不一致", "确认是否保留延时或调整计划");
        }
        return new PlanValidationView.Dimension("timing", PASSED,
                planHasDelay ? "计划含 D+ 延迟档" : "计划全为 D+0 即时",
                dagHasDelay ? "工作流含 DELAY 节点" : "工作流无 DELAY 节点",
                "延迟档位与工作流延时配置一致", "无");
    }

    private PlanValidationView.Dimension templateDimension(List<PlanDocument.PlanRouteRow> routes, Long tenantId) {
        List<PlanValidationView.Dimension> perRow = new ArrayList<>();
        for (PlanDocument.PlanRouteRow r : routes) {
            Template t = templateMapper.selectOne(new LambdaQueryWrapper<Template>()
                    .eq(Template::getTenantId, tenantId)
                    .eq(Template::getName, r.templateName())
                    .last("LIMIT 1"));
            if (t == null) {
                perRow.add(new PlanValidationView.Dimension("template", BLOCKED,
                        "模板 " + r.templateName(), "模板表无此名称",
                        "计划引用的消息模板不存在，执行将结构失败", "在模板管理中创建同名模板"));
                continue;
            }
            if (!normalizeChannel(t.getChannel()).equals(normalizeChannel(r.channel()))) {
                perRow.add(new PlanValidationView.Dimension("template", WARNINGS,
                        "模板 " + r.templateName() + "（通道 " + t.getChannel() + "）",
                        "计划通道 " + r.channel(),
                        "模板所属通道与计划行通道不一致，实际按模板通道发送", "统一模板通道与计划通道"));
            }
        }
        List<PlanValidationView.Dimension> blocked = perRow.stream().filter(d -> BLOCKED.equals(d.level())).toList();
        List<PlanValidationView.Dimension> warned = perRow.stream().filter(d -> WARNINGS.equals(d.level())).toList();
        if (!blocked.isEmpty()) {
            return new PlanValidationView.Dimension("template", BLOCKED,
                    "计划模板 " + routes.stream().map(PlanDocument.PlanRouteRow::templateName).distinct().toList(),
                    "模板表",
                    blocked.stream().map(PlanValidationView.Dimension::detail).distinct().collect(Collectors.joining("；")),
                    "创建缺失模板后重新导入");
        }
        if (!warned.isEmpty()) {
            return new PlanValidationView.Dimension("template", WARNINGS,
                    "计划模板", "模板表",
                    warned.stream().map(PlanValidationView.Dimension::detail).distinct().collect(Collectors.joining("；")),
                    "统一模板通道与计划通道");
        }
        return new PlanValidationView.Dimension("template", PASSED,
                "计划模板 " + routes.stream().map(PlanDocument.PlanRouteRow::templateName).distinct().toList(),
                "模板表", "计划引用的模板均存在", "无");
    }

    private PlanValidationView.Dimension frequencyDimension(List<PlanDocument.PlanRouteRow> routes) {
        for (PlanDocument.PlanRouteRow r : routes) {
            if (r.frequencyLimit() != null && r.frequencyLimit() > 1) {
                return new PlanValidationView.Dimension("frequency", BLOCKED,
                        "单用户频率上限 " + r.frequencyLimit(),
                        "系统 24h 窗口每 (用户,通道) 单次",
                        "计划要求单用户多次触达，将触碰 FrequencyGuard 用户窗口拦截",
                        "将频率上限调整为 1 或拆分不同通道");
            }
        }
        return new PlanValidationView.Dimension("frequency", PASSED,
                "计划频率上限 ≤1", "系统 24h 每 (用户,通道) 单次",
                "频率合规", "无");
    }

    private PlanValidationView.Dimension audienceDimension(List<PlanDocument.PlanAudienceRule> rules) {
        if (rules.isEmpty()) {
            return new PlanValidationView.Dimension("audience", PASSED,
                    "计划无人群规则", "—", "无人群规则需要比对", "无");
        }
        return new PlanValidationView.Dimension("audience", PASSED,
                    rules.size() + " 条人群规则", "—",
                    "人群规则语义比对待 LLM 里程碑启用；当前确定性模式不阻断",
                    "如需语义比对，等待 LLM 校验能力开放");
    }

    private PlanValidationView.Dimension copyNotesDimension(List<PlanDocument.PlanCopyNote> notes, Long tenantId) {
        List<String> missing = new ArrayList<>();
        for (PlanDocument.PlanCopyNote n : notes) {
            if (n.template() == null || n.template().isBlank()) {
                continue;
            }
            Template t = templateMapper.selectOne(new LambdaQueryWrapper<Template>()
                    .eq(Template::getTenantId, tenantId)
                    .eq(Template::getName, n.template())
                    .last("LIMIT 1"));
            if (t == null) {
                missing.add(n.template());
            }
        }
        if (!missing.isEmpty()) {
            return new PlanValidationView.Dimension("copy_notes", BLOCKED,
                    "文案要求模板 " + missing, "模板表无此名称",
                    "文案要求引用的模板不存在", "创建缺失模板后重新导入");
        }
        if (notes.isEmpty()) {
            return new PlanValidationView.Dimension("copy_notes", PASSED,
                    "计划无文案要求", "—", "无文案要求需要比对", "无");
        }
        return new PlanValidationView.Dimension("copy_notes", PASSED,
                    notes.size() + " 条文案要求", "—",
                    "文案语义比对待 LLM 里程碑启用；当前确定性模式不阻断",
                    "如需语义比对，等待 LLM 校验能力开放");
    }

    // ---------- 辅助 ----------

    /** 最新可用行：draft 优先，其次最新 published（与 WorkflowService.editableRow 一致）。 */
    private Workflow editableRow(Long refId) {
        Workflow draft = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, refId)
                .eq(Workflow::getStatus, "draft")
                .last("LIMIT 1"));
        if (draft != null) {
            return draft;
        }
        return workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, refId)
                .eq(Workflow::getStatus, "published")
                .orderByDesc(Workflow::getVersion)
                .last("LIMIT 1"));
    }

    private JsonNode triggerConfigNode(List<WorkflowNode> nodes) {
        for (WorkflowNode n : nodes) {
            if ("TRIGGER".equalsIgnoreCase(n.getType())) {
                try {
                    return n.getConfig() == null ? json.createObjectNode() : json.readTree(n.getConfig());
                } catch (Exception e) {
                    return json.createObjectNode();
                }
            }
        }
        return null;
    }

    /** 从 TRIGGER 沿出边拓扑序提取 ACTION 链 channel 序列。 */
    private List<String> actionChannelChain(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Map<String, WorkflowNode> byKey = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            byKey.put(n.getNodeKey(), n);
        }
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (WorkflowEdge e : edges) {
            outgoing.computeIfAbsent(e.getSourceKey(), k -> new ArrayList<>()).add(e.getTargetKey());
        }
        String start = byKey.values().stream()
                .filter(n -> "TRIGGER".equalsIgnoreCase(n.getType()))
                .map(WorkflowNode::getNodeKey).findFirst().orElse(null);
        List<String> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        if (start != null) {
            walk(start, outgoing, byKey, visited, chain);
        }
        return chain;
    }

    private void walk(String key, Map<String, List<String>> outgoing, Map<String, WorkflowNode> byKey,
                      Set<String> visited, List<String> chain) {
        if (!visited.add(key)) {
            return;
        }
        WorkflowNode n = byKey.get(key);
        if (n != null && "ACTION".equalsIgnoreCase(n.getType())) {
            chain.add(extractChannel(n.getConfig()));
        }
        for (String next : outgoing.getOrDefault(key, List.of())) {
            walk(next, outgoing, byKey, visited, chain);
        }
    }

    private String extractChannel(String config) {
        if (config == null) {
            return "";
        }
        try {
            JsonNode node = json.readTree(config);
            String ch = node.path("channel").asText("");
            return normalizeChannel(ch);
        } catch (Exception e) {
            return "";
        }
    }

    /** 计划触发方式归一化：TIMED/EVENT/MANUAL（模板口径）。 */
    private String normalizePlanTrigger(String raw) {
        if (raw == null) {
            return "MANUAL";
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.contains("IMED") || t.equals("定时")) {
            return "TIMED";
        }
        if (t.contains("VENT")) {
            return "EVENT";
        }
        return "MANUAL";
    }

    /** 通道归一化：小写 trim。 */
    private String normalizeChannel(String ch) {
        return ch == null ? "" : ch.trim().toLowerCase(Locale.ROOT);
    }

    /** timing "D+n" 延迟天数；非 D+ 格式或 D+0 → 0。 */
    private int parseDelayDays(String timing) {
        if (timing == null) {
            return 0;
        }
        String t = timing.trim().toUpperCase(Locale.ROOT);
        int idx = t.indexOf("D+");
        if (idx < 0) {
            return 0;
        }
        String rest = t.substring(idx + 2);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < rest.length() && Character.isDigit(rest.charAt(i)); i++) {
            digits.append(rest.charAt(i));
        }
        if (digits.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(digits.toString());
    }

    private String summarize(PlanDocument doc) {
        PlanDocument.PlanOverview ov = doc.overview();
        String trigger = switch (normalizePlanTrigger(ov.triggerType())) {
            case "TIMED" -> "定时 " + (ov.triggerTime() == null ? "" : ov.triggerTime());
            case "EVENT" -> "事件 " + (ov.eventName() == null ? "" : ov.eventName());
            default -> "手动";
        };
        String channels = doc.routes().stream()
                .sorted(java.util.Comparator.comparing(PlanDocument.PlanRouteRow::sequence))
                .map(PlanDocument.PlanRouteRow::channel).collect(Collectors.joining(" → "));
        return "计划「" + ov.planName() + "」面向" + (ov.audienceTarget() == null ? "-" : ov.audienceTarget())
                + "；触发：" + trigger + "；通道链：" + channels;
    }
}