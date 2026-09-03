package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.dto.workflow.DryRunResponse;
import com.easysys.api.dto.workflow.WorkflowSummaryView;
import com.easysys.api.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 助手工具：trigger_workflow —— AI 触发的真实工作流执行（与 plan_workflow 同 HITL 模式：
 * <b>checkPermissions 返回 ask(...)</b>，框架发射 RequireUserConfirmEvent 悬挂本轮，
 * 用户确认后执行，取消则结果 DENIED，对话按「已取消」收尾）。
 *
 * <p>执行真实触达（WorkflowService.execute → 动作节点真发），前置校验（未发布/缺人群节点）
 * 由执行链路以 BizException 拦截，工具捕获后产出 ERROR 结果文本。
 */
@Component
public class AssistantTriggerWorkflowTool extends AssistantToolBase {

    private final WorkflowService workflowService;
    private final ObjectMapper json;

    public AssistantTriggerWorkflowTool(WorkflowService workflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_TRIGGER_WORKFLOW)
                .description("AI 触发执行一个已发布的工作流（真实触达；执行前需人工确认）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("workflowId", Map.of("type", "integer", "description", "目标工作流 id")),
                        "required", java.util.List.of("workflowId")))
                .readOnly(false)
                .concurrencySafe(false));
        this.workflowService = workflowService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.ask("触发工作流执行需人工确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 工具线程无 TenantContext：execute 内部走租户拦截器，先注入租户
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Object wfId = param.getInput() == null ? null : param.getInput().get("workflowId");
            if (wfId == null || !(wfId instanceof Number)) {
                throw new IllegalArgumentException("trigger_workflow 缺少 workflowId 参数");
            }
            long id = ((Number) wfId).longValue();
            DryRunResponse r = workflowService.execute(id);
            ObjectNode out = json.createObjectNode()
                    .put("executionId", r.executionId() == null ? 0 : r.executionId())
                    .put("workflowId", r.workflowId() == null ? id : r.workflowId())
                    .put("workflowName", workflowName(id))
                    .put("workflowVersion", r.workflowVersion() == null ? 0 : r.workflowVersion())
                    .put("status", r.status())
                    .put("totalMembers", r.totalMembers())
                    .put("dryRun", r.dryRun())
                    .put("durationMs", r.durationMs());
            if (r.error() != null) {
                out.put("error", r.error());
            }
            String id2 = param.getToolUseBlock().getId();
            return new ToolResultBlock(id2, AssistantPolicy.TOOL_TRIGGER_WORKFLOW,
                    TextBlock.builder().text(out.toString()).build());
        })).onErrorResume(e -> errorResult(param, AssistantPolicy.TOOL_TRIGGER_WORKFLOW, json, e));
    }

    private String workflowName(long id) {
        for (WorkflowSummaryView w : workflowService.list()) {
            if (w.id() != null && w.id() == id) {
                return w.name() == null ? "" : w.name();
            }
        }
        return "";
    }
}