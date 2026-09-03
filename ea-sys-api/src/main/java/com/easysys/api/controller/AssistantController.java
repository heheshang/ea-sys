package com.easysys.api.controller;

import com.easysys.agent.AssistantPolicy;
import com.easysys.api.assistant.KnowledgeBaseService;
import com.easysys.api.dto.assistant.KbDocumentView;
import com.easysys.api.dto.workflow.AiChatRequest;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.ApiResponse;
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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 智能客服：POST /api/assistant/ai-chat（SSE 事件流）+ 知识库文档管理
 * （POST/GET /api/assistant/documents、DELETE /api/assistant/documents/{id}）。
 *
 * <p>事件 payload 统一 {@code {type: <AgentEventType>, ...字段}}（与 /api/workflows/ai-chat 同构，
 * 前端可复用同一套事件帧解析），额外自定义事件：
 * <ul>
 *   <li>{@code assistant_card: {type: "assistant_card", kind: kb|stats|audiences|workflows|trigger,
 *       data: <工具结果 JSON>}} —— 工具成功完成时由结果分片重建并下发（引用卡片 / 统计卡片 /
 *       人群卡片 / 工作流列表卡片 / 触发结果卡片）；</li>
 *   <li>{@code switch_workflow_dialogue: {type: "switch_workflow_dialogue"}} ——
 *       begin_workflow_dialogue 成功时下发，前端据此切换到工作流创建会话。</li>
 * </ul>
 *
 * <p>HITL 挂起（trigger_workflow ASKING）语义与 /api/workflows/ai-chat 的 plan_workflow 一致：
 * 挂起期间前端强制先确认/取消；后端防御：无 confirm 字段且会话存在 ASKING 工具时抛 409。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** 工具名 → 卡片 kind。 */
    private static final Map<String, String> CARD_KIND_BY_TOOL = Map.of(
            AssistantPolicy.TOOL_SEARCH_KB, "kb",
            AssistantPolicy.TOOL_QUERY_STATS, "stats",
            AssistantPolicy.TOOL_SEARCH_AUDIENCES, "audiences",
            AssistantPolicy.TOOL_SEARCH_WORKFLOWS, "workflows",
            AssistantPolicy.TOOL_TRIGGER_WORKFLOW, "trigger");

    /** HITL 挂起会话 → 待确认的 ToolUseBlock（RequireUserConfirmEvent 为准，state 回退）。 */
    private final ConcurrentHashMap<String, ToolUseBlock> pendingAsks = new ConcurrentHashMap<>();
    /** 工具结果 JSON 分片累积（key = userId:sessionId:toolCallId，按调用隔离并发工具轮）。 */
    private final ConcurrentHashMap<String, StringBuilder> toolOutputs = new ConcurrentHashMap<>();

    private final HarnessAgent agent;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper json;

    public AssistantController(@Qualifier("assistantAgent") HarnessAgent agent,
                               KnowledgeBaseService knowledgeBaseService, ObjectMapper json) {
        this.agent = agent;
        this.knowledgeBaseService = knowledgeBaseService;
        this.json = json;
    }

    // ---- 对话（SSE） ----

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

    // ---- 事件 → SSE 转发（含卡片重建） ----

    private void send(SseEmitter emitter, AgentEvent ev, String userId, String sessionId) {
        if (ev instanceof RequireUserConfirmEvent e && !e.getToolCalls().isEmpty()) {
            pendingAsks.put(sessionKey(userId, sessionId), e.getToolCalls().get(0));
        } else if (ev instanceof AgentResultEvent || ev instanceof UserConfirmResultEvent) {
            pendingAsks.remove(sessionKey(userId, sessionId));
        }
        try {
            emitter.send(SseEmitter.event().data(toJson(ev)));
            if (ev instanceof ToolResultTextDeltaEvent e) {
                String tool = e.getToolCallName();
                if (CARD_KIND_BY_TOOL.containsKey(tool)
                        || AssistantPolicy.TOOL_BEGIN_WORKFLOW_DIALOGUE.equals(tool)) {
                    // 工具结果分片累积：卡片 JSON 在 ToolResultEndEvent 时重建
                    accumulate(userId, sessionId, e.getToolCallId(), e.getDelta());
                }
            } else if (ev instanceof ToolResultEndEvent e
                    && e.getState() != null
                    && "SUCCESS".equals(e.getState().name())) {
                JsonNode toolResult = drain(userId, sessionId, e.getToolCallId());
                String kind = CARD_KIND_BY_TOOL.get(e.getToolCallName());
                if (kind != null && toolResult != null) {
                    ObjectNode payload = json.createObjectNode();
                    payload.put("type", "assistant_card");
                    payload.put("kind", kind);
                    payload.set("data", toolResult);
                    emitter.send(SseEmitter.event().data(payload));
                } else if (AssistantPolicy.TOOL_BEGIN_WORKFLOW_DIALOGUE.equals(e.getToolCallName())) {
                    ObjectNode payload = json.createObjectNode();
                    payload.put("type", "switch_workflow_dialogue");
                    emitter.send(SseEmitter.event().data(payload));
                }
            }
        } catch (Exception e) {
            // 客户端断开（AsyncRequestNotUsableException 等）：停止本流，忽略后续
        }
    }

    private void accumulate(String userId, String sessionId, String toolCallId, String delta) {
        toolOutputs.computeIfAbsent(key(userId, sessionId, toolCallId), k -> new StringBuilder())
                .append(delta);
    }

    /** 读取工具结果 JSON（分片累积优先；无分片时从会话状态上下文回退）。 */
    private JsonNode drain(String userId, String sessionId, String toolCallId) {
        String k = key(userId, sessionId, toolCallId);
        StringBuilder sb = toolOutputs.remove(k);
        String text = sb == null ? null : sb.toString();
        JsonNode node = parseJson(text);
        if (node != null) {
            return node;
        }
        // 单块小结果可能无分片事件：从代理状态上下文定位同 id 结果块
        AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
        if (state == null || state.getContext() == null) {
            return null;
        }
        for (int i = state.getContext().size() - 1; i >= 0; i--) {
            for (ToolResultBlock r : state.getContext().get(i).getContentBlocks(ToolResultBlock.class)) {
                if (toolCallId.equals(r.getId())) {
                    return parseJson(extractText(r));
                }
            }
        }
        return null;
    }

    private static String extractText(ToolResultBlock r) {
        for (var b : r.getOutput()) {
            if (b instanceof io.agentscope.core.message.TextBlock tb) {
                return tb.getText();
            }
        }
        return null;
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (!s.startsWith("{") && !s.startsWith("[")) {
            return null;
        }
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return null;
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

    // ---- 消息组装（含 HITL 确认回填） ----

    private Msg buildMsg(AiChatRequest req, ReActAgent delegate, String userId, String sessionId) {
        ToolUseBlock pending = pendingAsk(delegate, userId, sessionId);
        if (req.confirm() == null) {
            if (pending != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "存在待确认的操作，请先确认或取消");
            }
            return new UserMessage(req.message());
        }
        if (pending == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有待确认的操作（可能已处理）");
        }
        ConfirmResult decision = new ConfirmResult(req.confirm().confirmed(), pending);
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(req.message())
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(decision)))
                .build();
    }

    /** 查找会话中任意挂起（ASKING）的工具调用：事件缓存优先，state 反序列化回退。 */
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
                if (tub.getState() == ToolCallState.ASKING) {
                    return tub;
                }
            }
        }
        return null;
    }

    private static String sessionKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private static String key(String userId, String sessionId, String toolCallId) {
        return sessionKey(userId, sessionId) + ":" + toolCallId;
    }

    // ---- 知识库文档管理 ----

    /** 上传文档（txt/md/csv/xlsx/docx/pdf，≤10MB），解析分块入知识库。 */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KbDocumentView> uploadDocument(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        KbDocumentView view = knowledgeBaseService.upload(
                file.getOriginalFilename(), file.getSize(), file.getBytes());
        return ApiResponse.ok(view);
    }

    /** 文档列表（含解析状态/分块数/错误）。 */
    @GetMapping("/documents")
    public ApiResponse<List<KbDocumentView>> listDocuments() {
        return ApiResponse.ok(knowledgeBaseService.listDocuments());
    }

    /** 删除文档（软删文档行 + 物理删除分块）。 */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        knowledgeBaseService.deleteDocument(id);
        return ApiResponse.ok(null);
    }
}