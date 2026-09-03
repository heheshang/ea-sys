package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.dto.retention.ChannelEffectView;
import com.easysys.api.dto.retention.FunnelView;
import com.easysys.api.dto.retention.IntervalRetentionView;
import com.easysys.api.dto.retention.WorkflowEffectView;
import com.easysys.api.service.RetentionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 助手工具：query_stats —— 运营数据问答（topic ∈ channel|retention|funnel|workflow），
 * 直接读 RetentionService 实时指标并输出带中文标签的 JSON（前端渲染统计卡片）。
 */
@Component
public class AssistantQueryStatsTool extends AssistantToolBase {

    private static final Set<String> TOPICS = Set.of("channel", "retention", "funnel", "workflow");

    private final RetentionService retentionService;
    private final ObjectMapper json;

    public AssistantQueryStatsTool(RetentionService retentionService, ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_QUERY_STATS)
                .description("查询运营数据：到达率/送达率(channel)、留存率(retention)、转化漏斗(funnel)、工作流效果(workflow)")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("topic", Map.of("type", "string",
                                "enum", TOPICS.stream().sorted().toList(),
                                "description", "指标主题")),
                        "required", java.util.List.of("topic")))
                .readOnly(true)
                .concurrencySafe(true));
        this.retentionService = retentionService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读查询"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Object topic = param.getInput() == null ? null : param.getInput().get("topic");
            if (topic == null || !TOPICS.contains(topic.toString())) {
                throw new IllegalArgumentException("query_stats 的 topic 仅支持 " + TOPICS + "，收到：" + topic);
            }
            ObjectNode body = json.createObjectNode();
            body.put("topic", topic.toString());
            fill(topic.toString(), body);
            ObjectNode root = json.createObjectNode();
            root.set("topics", json.createArrayNode().add(body));
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, AssistantPolicy.TOOL_QUERY_STATS,
                    TextBlock.builder().text(root.toString()).build());
        })).onErrorResume(e -> errorResult(param, AssistantPolicy.TOOL_QUERY_STATS, json, e));
    }

    private void fill(String topic, ObjectNode body) {
        switch (topic) {
            case "channel" -> {
                ChannelEffectView v = retentionService.channelEffect(Instant.now().minus(Duration.ofDays(7)), null);
                ArrayNode arr = json.createArrayNode();
                for (ChannelEffectView.ChannelEffectItem c : v.channels()) {
                    arr.addObject()
                            .put("channel", c.channel())
                            .put("total", c.total())
                            .put("sent", c.sent())
                            .put("failed", c.failed())
                            .put("distinctContacts", c.distinctContacts())
                            .put("deliveryRate", c.deliveryRate());
                }
                body.set("items", arr);
            }
            case "retention" -> {
                IntervalRetentionView v = retentionService.intervalRetention(30);
                body.put("days", v.days());
                body.put("cohort", v.cohort());
                body.put("retained", v.retained());
                body.put("rate", v.rate());
                body.put("priorWindowStart", v.priorWindowStart().toString());
                body.put("priorWindowEnd", v.priorWindowEnd().toString());
                body.put("currentWindowStart", v.currentWindowStart().toString());
                body.put("currentWindowEnd", v.currentWindowEnd().toString());
            }
            case "funnel" -> {
                FunnelView v = retentionService.funnel(null);
                if (v.workflowId() != null) {
                    body.put("workflowId", v.workflowId());
                }
                body.put("seeded", v.seeded());
                body.put("executed", v.executed());
                body.put("reached", v.reached());
                body.put("seededToExecutedRate", v.seededToExecutedRate());
                body.put("executedToReachedRate", v.executedToReachedRate());
            }
            case "workflow" -> {
                WorkflowEffectView v = retentionService.workflowEffect(30);
                ArrayNode arr = json.createArrayNode();
                for (WorkflowEffectView.WorkflowEffectItem w : v.workflows()) {
                    arr.addObject()
                            .put("workflowId", w.workflowId())
                            .put("workflowName", w.workflowName() == null ? "" : w.workflowName())
                            .put("reached", w.reached())
                            .put("retained", w.retained())
                            .put("retentionRate", w.retentionRate());
                }
                body.set("items", arr);
            }
            default -> throw new IllegalArgumentException("未知指标主题：" + topic);
        }
    }
}