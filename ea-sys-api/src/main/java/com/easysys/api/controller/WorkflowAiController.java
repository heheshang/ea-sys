package com.easysys.api.controller;

import com.easysys.agent.WorkflowDialoguePolicy;
import com.easysys.api.dto.workflow.AiChatRequest;
import com.easysys.api.service.AiWorkflowService;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话式创建工作流：POST /api/workflows/ai-chat（SSE 事件流）。
 *
 * <p>一轮请求 = 一次 HarnessAgent 会话续跑：框架 ReAct 模型位（WorkflowDialogueModel，
 * 确定性）决策 → 查询工具轮（list_channels/search_templates/search_audiences）→
 * 缺项追问 / plan_workflow 生成轮（HITL ask）→ RequireUserConfirmEvent 挂起 →
 * 用户确认/取消消息回填 → 工具执行 → AgentResultEvent + draft_ready（草稿 JSON 卡片）。
 *
 * <p>事件 payload 统一 {@code {type: <AgentEventType>, ...字段}}（type 值见
 * {@code io.agentscope.core.event.AgentEventType}），额外自定义事件
 * {@code draft_ready: {type: "draft_ready", draft: <AiGenerateResponse JSON>}}。
 *
 * <p>HITL 挂起期间（Ask 未决）前端强制先确认/取消；后端防御：无 confirm 字段且
 * 会话 context 存在 ASKING 的 plan_workflow 时抛 409（普通非流式错误）。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowAiController {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** HITL 挂起会话 → 待确认的 ToolUseBlock（来自 RequireUserConfirmEvent，非反序列化 state）。 */
    private final ConcurrentHashMap<String, ToolUseBlock> pendingAsks = new ConcurrentHashMap<>();
    /** 会话 → plan_workflow 输出 JSON 累积（工具结果事件分片，待 AGENT_RESULT 发布 draft_ready）。 */
    private final ConcurrentHashMap<String, StringBuilder> pendingDrafts = new ConcurrentHashMap<>();

    private final HarnessAgent agent;
    private final AiWorkflowService aiWorkflowService;
    private final ObjectMapper json;

    public WorkflowAiController(HarnessAgent agent, AiWorkflowService aiWorkflowService, ObjectMapper json) {
        this.agent = agent;
        this.aiWorkflowService = aiWorkflowService;
        this.json = json;
    }

    @PostMapping(value = "/ai-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiChat(@Valid @RequestBody AiChatRequest req, @RequestAttribute String username) {
        Long tenantId = TenantContext.require();
        String userId = String.valueOf(tenantId);

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(req.sessionId())
                .put("tenantId", Long.class, tenantId)
                .put("operator", String.class, username)
                .build();

        ReActAgent delegate = agent.getDelegate();
        Msg msg = buildMsg(req, delegate, userId, req.sessionId());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Flux<AgentEvent> events = delegate.streamEvents(List.of(msg), ctx);
        events.subscribe(
                ev -> send(emitter, ev, userId, req.sessionId()),
                err -> {
                    try {
                        emitter.completeWithError(err);
                    } catch (Exception ignored) {
                        // 客户端已断开
                    }
                },
                emitter::complete);
        return emitter;
    }

    // ---- 事件 → SSE 转发 ----

    private void send(SseEmitter emitter, AgentEvent ev, String userId, String sessionId) {
        if (ev instanceof RequireUserConfirmEvent e && !e.getToolCalls().isEmpty()) {
            // 挂起块以事件为准缓存：state 反序列化可能丢失 input，回填执行会参数校验失败
            pendingAsks.put(sessionKey(userId, sessionId), e.getToolCalls().get(0));
        } else if (ev instanceof AgentResultEvent || ev instanceof UserConfirmResultEvent) {
            // 挂起已解决（确认/取消执行完毕）
            pendingAsks.remove(sessionKey(userId, sessionId));
        }
        try {
            emitter.send(SseEmitter.event().data(toJson(ev)));
            if (ev instanceof ToolResultTextDeltaEvent e
                    && WorkflowDialoguePolicy.TOOL_PLAN_WORKFLOW.equals(e.getToolCallName())) {
                // 累积 plan_workflow 的 JSON 输出：AgentResultEvent 的最终消息只有结语
                // 文本，不含工具结果块，草稿必须从事件流的工具输出重建。
                pendingDrafts.computeIfAbsent(sessionKey(userId, sessionId), k -> new StringBuilder())
                        .append(e.getDelta());
            } else if (ev instanceof AgentResultEvent) {
                StringBuilder sb = pendingDrafts.remove(sessionKey(userId, sessionId));
                if (sb != null && !sb.isEmpty()) {
                    JsonNode draft = parseJson(sb.toString());
                    if (draft != null) {
                        ObjectNode payload = json.createObjectNode();
                        payload.put("type", "draft_ready");
                        payload.set("draft", draft);
                        emitter.send(SseEmitter.event().data(payload));
                    }
                }
            }
        } catch (Exception e) {
            // 客户端断开（AsyncRequestNotUsableException 等）：停止本流，忽略后续
        }
    }

    private ObjectNode toJson(AgentEvent ev) {
        ObjectNode o = json.createObjectNode();
        o.put("type", ev.getType().name());
        if (ev instanceof TextBlockDeltaEvent e) {
            o.put("blockId", e.getBlockId());
            o.put("delta", e.getDelta());
        } else if (ev instanceof TextBlockEndEvent e) {
            o.put("blockId", e.getBlockId());
        } else if (ev instanceof ToolCallStartEvent e) {
            o.put("toolCallId", e.getToolCallId());
            o.put("toolCallName", e.getToolCallName());
        } else if (ev instanceof ToolResultTextDeltaEvent e) {
            o.put("toolCallId", e.getToolCallId());
            o.put("toolCallName", e.getToolCallName());
            o.put("delta", e.getDelta());
        } else if (ev instanceof ToolResultEndEvent e) {
            o.put("toolCallId", e.getToolCallId());
            o.put("toolCallName", e.getToolCallName());
            o.put("state", e.getState() == null ? null : e.getState().name());
        } else if (ev instanceof RequireUserConfirmEvent e) {
            o.put("replyId", e.getReplyId());
            ArrayNode calls = o.putArray("toolCalls");
            for (ToolUseBlock tub : e.getToolCalls()) {
                ObjectNode t = calls.addObject();
                t.put("id", tub.getId());
                t.put("name", tub.getName());
                t.set("input", json.valueToTree(tub.getInput()));
            }
        } else if (ev instanceof UserConfirmResultEvent e) {
            o.put("replyId", e.getReplyId());
            ArrayNode results = o.putArray("confirmResults");
            for (ConfirmResult cr : e.getConfirmResults()) {
                results.addObject().put("confirmed", cr.isConfirmed());
            }
        } else if (ev instanceof AgentResultEvent e) {
            Msg result = e.getResult();
            o.put("summary", result == null ? "" : String.valueOf(result.getTextContent()));
        }
        return o;
    }

    private JsonNode parseJson(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 消息组装（含 HITL 确认回填） ----

    private Msg buildMsg(AiChatRequest req, ReActAgent delegate, String userId, String sessionId) {
        ToolUseBlock pending = pendingAsk(delegate, userId, sessionId);
        if (req.confirm() == null) {
            if (pending != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "存在待确认的生成请求，请先确认或取消");
            }
            return new UserMessage(req.message());
        }
        if (pending == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有待确认的生成请求（可能已处理）");
        }
        ConfirmResult decision = new ConfirmResult(req.confirm().confirmed(), pending);
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(req.message())
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(decision)))
                .build();
    }

    /** 查找会话挂起（ASKING）的 plan_workflow 工具调用：事件缓存优先，state 反序列化回退。 */
    private ToolUseBlock pendingAsk(ReActAgent delegate, String userId, String sessionId) {
        ToolUseBlock cached = pendingAsks.get(sessionKey(userId, sessionId));
        if (cached != null) {
            return cached;
        }
        AgentState state = delegate.getAgentState(userId, sessionId);
        if (state == null || state.getContext() == null) {
            return null;
        }
        for (int i = state.getContext().size() - 1; i >= 0; i--) {
            for (ToolUseBlock tub : state.getContext().get(i).getContentBlocks(ToolUseBlock.class)) {
                if (WorkflowDialoguePolicy.TOOL_PLAN_WORKFLOW.equals(tub.getName())
                        && tub.getState() == ToolCallState.ASKING) {
                    return tub;
                }
            }
        }
        return null;
    }

    private static String sessionKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}