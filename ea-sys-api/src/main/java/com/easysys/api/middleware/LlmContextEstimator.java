package com.easysys.api.middleware;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文构成六类估算（驾驶舱 LLM 卡 + llm_usage 记账共用同一实现，防口径漂移）。
 *
 * <p>输入 = 模型输入消息列表 + 工具 Schema 列表；输出紧凑 JSON
 * {@code {entries, tokens, categories:[{key,entries,tokens}]}}。字符折算估算
 * （chars/2.5 + 结构 overhead，与框架 compaction 同一启发式，不引入 tokenizer 依赖）。
 * 占比分母 = 构成总和自身。类别口径：system 系统提示词 / tool_schema 工具Schema /
 * user 用户消息 / assistant 助手消息（含 ToolUseBlock）/ injected 注入上下文（synthetic
 * 元数据）/ tool_result 工具结果。
 */
public final class LlmContextEstimator {

    private static final Logger log = LoggerFactory.getLogger(LlmContextEstimator.class);

    /** 与框架 TokenCounterUtil 同款估算常量。 */
    private static final double CHARS_PER_TOKEN = 2.5;
    private static final int MESSAGE_OVERHEAD = 5;
    private static final int TOOL_CALL_OVERHEAD = 10;
    private static final int TOOL_RESULT_OVERHEAD = 8;

    private static final List<String> CATEGORY_ORDER = List.of(
            "system", "tool_schema", "user", "assistant", "injected", "tool_result");

    private LlmContextEstimator() {
    }

    /** 统计输入（messages + tools）六类构成；估算异常返回 null（调用方降级展示）。 */
    public static String compose(List<Msg> messages, List<ToolSchema> tools) {
        try {
            Map<String, long[]> counts = new LinkedHashMap<>();
            for (String key : CATEGORY_ORDER) {
                counts.put(key, new long[]{0, 0}); // [entries, tokens]
            }
            if (messages != null) {
                for (Msg msg : messages) {
                    String key = categoryOf(msg);
                    long[] c = counts.get(key);
                    c[0]++;
                    c[1] += estimateMsgTokens(msg);
                }
            }
            if (tools != null) {
                long[] c = counts.get("tool_schema");
                for (ToolSchema tool : tools) {
                    c[0]++;
                    c[1] += estimateToolSchemaTokens(tool);
                }
            }

            long totalEntries = 0;
            long totalTokens = 0;
            for (long[] c : counts.values()) {
                totalEntries += c[0];
                totalTokens += c[1];
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"entries\":").append(totalEntries)
                    .append(",\"tokens\":").append(totalTokens)
                    .append(",\"categories\":[");
            boolean first = true;
            for (String key : CATEGORY_ORDER) {
                long[] c = counts.get(key);
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"key\":\"").append(key).append("\",\"entries\":").append(c[0])
                        .append(",\"tokens\":").append(c[1]).append('}');
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            log.warn("上下文构成估算失败（跳过构成，仅记用量）: {}", e.getMessage());
            return null;
        }
    }

    /** 注入上下文（synthetic 元数据）优先于角色归类；其余按角色。 */
    private static String categoryOf(Msg msg) {
        if (msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))) {
            return "injected";
        }
        MsgRole role = msg.getRole();
        if (role == MsgRole.SYSTEM) {
            return "system";
        }
        if (role == MsgRole.USER) {
            return "user";
        }
        if (role == MsgRole.ASSISTANT) {
            return "assistant";
        }
        return "tool_result";
    }

    private static int estimateMsgTokens(Msg msg) {
        if (msg == null) {
            return 0;
        }
        int tokens = MESSAGE_OVERHEAD;
        if (msg.getRole() != null) {
            tokens += estimateTextTokens(msg.getRole().name());
        }
        if (msg.getName() != null) {
            tokens += estimateTextTokens(msg.getName());
        }
        List<ContentBlock> content = msg.getContent();
        if (content != null) {
            for (ContentBlock block : content) {
                tokens += estimateBlockTokens(block);
            }
        }
        return tokens;
    }

    private static int estimateBlockTokens(ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            return estimateTextTokens(textBlock.getText());
        }
        if (block instanceof ToolUseBlock toolUseBlock) {
            int tokens = TOOL_CALL_OVERHEAD;
            if (toolUseBlock.getName() != null) {
                tokens += estimateTextTokens(toolUseBlock.getName());
            }
            if (toolUseBlock.getId() != null) {
                tokens += estimateTextTokens(toolUseBlock.getId());
            }
            Map<String, Object> input = toolUseBlock.getInput();
            if (input != null && !input.isEmpty()) {
                tokens += estimateTextTokens(mapAsJson(input));
            }
            if (toolUseBlock.getContent() != null) {
                tokens += estimateTextTokens(toolUseBlock.getContent());
            }
            return tokens;
        }
        if (block instanceof ToolResultBlock toolResultBlock) {
            int tokens = TOOL_RESULT_OVERHEAD;
            if (toolResultBlock.getName() != null) {
                tokens += estimateTextTokens(toolResultBlock.getName());
            }
            if (toolResultBlock.getId() != null) {
                tokens += estimateTextTokens(toolResultBlock.getId());
            }
            List<ContentBlock> output = toolResultBlock.getOutput();
            if (output != null) {
                for (ContentBlock outputBlock : output) {
                    tokens += estimateBlockTokens(outputBlock);
                }
            }
            return tokens;
        }
        return 5; // 其他 block 类型（图片/音频等）按最小 overhead 估算
    }

    private static int estimateToolSchemaTokens(ToolSchema tool) {
        if (tool == null) {
            return 0;
        }
        int tokens = TOOL_CALL_OVERHEAD;
        if (tool.getName() != null) {
            tokens += estimateTextTokens(tool.getName());
        }
        if (tool.getDescription() != null) {
            tokens += estimateTextTokens(tool.getDescription());
        }
        Map<String, Object> parameters = tool.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            tokens += estimateTextTokens(mapAsJson(parameters));
        }
        Map<String, Object> outputSchema = tool.getOutputSchema();
        if (outputSchema != null && !outputSchema.isEmpty()) {
            tokens += estimateTextTokens(mapAsJson(outputSchema));
        }
        return tokens;
    }

    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** 简化 JSON 估算（键 + 标量值），与框架同款口径。 */
    private static String mapAsJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append('"').append(value).append('"');
            } else {
                sb.append(value != null ? value : "null");
            }
        }
        sb.append('}');
        return sb.toString();
    }
}