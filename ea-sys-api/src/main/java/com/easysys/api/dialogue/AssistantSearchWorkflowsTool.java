package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.dto.workflow.WorkflowSummaryView;
import com.easysys.api.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

/**
 * 助手工具：search_workflows —— 当前租户已发布工作流列表（触发候选；keyword 可选过滤名称）。
 * 恒输出数组 {@code [{id, name, status, version, description}]}（空数组 = 无可执行工作流），
 * 便于策略器做“唯一直接触发 / 多个点名匹配”判定。
 */
@Component
public class AssistantSearchWorkflowsTool extends AssistantToolBase {

    private final WorkflowService workflowService;
    private final ObjectMapper json;

    public AssistantSearchWorkflowsTool(WorkflowService workflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_SEARCH_WORKFLOWS)
                .description("查询当前租户已发布（可执行）的工作流列表，供用户选择触发")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("keyword", Map.of("type", "string", "description", "可选，按名称模糊过滤")),
                        "required", java.util.List.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.workflowService = workflowService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读查询"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Object keyword = param.getInput() == null ? null : param.getInput().get("keyword");
            String kw = keyword == null ? "" : keyword.toString().trim().toLowerCase(Locale.ROOT);
            ArrayNode arr = json.createArrayNode();
            for (WorkflowSummaryView w : workflowService.list()) {
                if (!"published".equalsIgnoreCase(w.status())) {
                    continue;
                }
                String name = w.name() == null ? "" : w.name();
                if (!kw.isBlank() && !name.toLowerCase(Locale.ROOT).contains(kw)) {
                    continue;
                }
                arr.addObject()
                        .put("id", w.id() == null ? 0 : w.id())
                        .put("name", name)
                        .put("status", w.status())
                        .put("version", w.version() == null ? 0 : w.version())
                        .put("description", w.description() == null ? "" : w.description());
            }
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, AssistantPolicy.TOOL_SEARCH_WORKFLOWS,
                    TextBlock.builder().text(arr.toString()).build());
        })).onErrorResume(e -> errorResult(param, AssistantPolicy.TOOL_SEARCH_WORKFLOWS, json, e));
    }
}