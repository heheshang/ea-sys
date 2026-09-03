package com.easysys.api.dialogue;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.dto.audience.AudienceResponse;
import com.easysys.api.service.AiWorkflowService;
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
 * 助手工具：search_audiences —— 人群圈定查询（当前租户人群列表；keyword 可选过滤名称，
 * 只读展示，不在对话中新建人群）。形状 {@code [{id, name, rule}]} 或 {@code {"note": ...}}。
 */
@Component
public class AssistantSearchAudiencesTool extends AssistantToolBase {

    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public AssistantSearchAudiencesTool(AiWorkflowService aiWorkflowService, ObjectMapper json) {
        super(ToolBase.builder()
                .name(AssistantPolicy.TOOL_SEARCH_AUDIENCES)
                .description("查询当前租户已保存的人群（人群圈定：含名称与圈选规则，供查看与匹配）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("keyword", Map.of("type", "string", "description", "可选，按名称模糊过滤")),
                        "required", java.util.List.of()))
                .readOnly(true)
                .concurrencySafe(true));
        this.aiWorkflowService = aiWorkflowService;
        this.json = json;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        return Mono.just(PermissionDecision.allow("只读查询"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return withTenant(requiredTenant(param), () -> Mono.fromCallable(() -> {
            Long tenantId = requiredTenant(param);
            Object keyword = param.getInput() == null ? null : param.getInput().get("keyword");
            String kw = keyword == null ? "" : keyword.toString().trim().toLowerCase(Locale.ROOT);
            ArrayNode arr = json.createArrayNode();
            for (AudienceResponse a : aiWorkflowService.searchAudiencesFor(tenantId)) {
                String name = a.name() == null ? "" : a.name();
                if (!kw.isBlank() && !name.toLowerCase(Locale.ROOT).contains(kw)) {
                    continue;
                }
                arr.addObject()
                        .put("id", a.id() == null ? 0 : a.id())
                        .put("name", name)
                        .put("rule", a.rule() == null ? "" : a.rule());
            }
            String text = arr.isEmpty()
                    ? json.createObjectNode().put("note", kw.isBlank() ? "当前租户暂无人群" : "没有名称包含「" + kw + "」的人群").toString()
                    : arr.toString();
            String id = param.getToolUseBlock().getId();
            return new ToolResultBlock(id, AssistantPolicy.TOOL_SEARCH_AUDIENCES,
                    TextBlock.builder().text(text).build());
        })).onErrorResume(e -> errorResult(param, AssistantPolicy.TOOL_SEARCH_AUDIENCES, json, e));
    }
}