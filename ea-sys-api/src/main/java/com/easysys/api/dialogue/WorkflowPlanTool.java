package com.easysys.api.dialogue;

import com.easysys.api.service.AiWorkflowService;
import com.easysys.api.dto.workflow.AiGenerateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
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
 * 对话工具：plan_workflow —— 生成工作流 DAG 草稿（触发/人群/通道/模板 → 节点边，
 * 输出 AiGenerateResponse JSON，含 workflowDraft/planSummary/toolCalls/audienceHint）。
 *
 * <p><b>HITL 闸门</b>：checkPermissions 返回 {@code ask(...)} —— 框架在真正执行前
 * 发射 RequireUserConfirmEvent 悬挂本轮（前端渲染确认卡片），用户确认后
 * （ConfirmResult(true, tub) 经 METADATA_CONFIRM_RESULTS 回填）才执行生成；
 * 取消则结果置 DENIED，对话轮回到模型按「已取消」收尾。框架级能力，与模型无关。
 */
@Component
public class WorkflowPlanTool extends WorkflowToolBase {

    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public WorkflowPlanTool(AiWorkflowService aiWorkflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name("plan_workflow")
                .description("生成工作流 DAG 草稿：根据已确认的触发时机/目标人群/通道/模板生成（生成前需人工确认）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("prompt", Map.of("type", "string", "description", "合并后的用户需求文本")),
                        "required", java.util.List.of("prompt")))
                .readOnly(false)
                .concurrencySafe(false));
        this.aiWorkflowService = aiWorkflowService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.ask("生成工作流草稿需人工确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 工具线程无 TenantContext：generateForTenant 内部走租户拦截器，先注入租户
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Long tenantId = requiredTenant(param);
            RuntimeContext rc = param.getRuntimeContext();
            String operator = rc.get("operator", String.class);
            Object prompt = param.getInput() == null ? null : param.getInput().get("prompt");
            if (prompt == null || prompt.toString().isBlank()) {
                throw new IllegalArgumentException("plan_workflow 缺少 prompt 参数");
            }
            AiGenerateResponse resp = aiWorkflowService.generateForTenant(tenantId, prompt.toString(), operator);
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, "plan_workflow",
                    TextBlock.builder().text(json.writeValueAsString(resp)).build());
        })).onErrorResume(e -> errorResult(param, "plan_workflow", json, e));
    }
}