package com.easysys.api.service;

import com.easysys.api.config.AgentLlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 真实判分器：LLM 主提供方启用（easysys.agent.llm enabled + apiKey）时，对选中
 * LLM-Judge 评测器逐 case 调 Judge 模型打分，judgeRounds 次取均值；判分失败/超时/空
 * 返回 → 返回 null，由调用方降级为确定性近似（不整轮失败）。
 *
 * <p>模型位与 harness 主链路一致：经 {@link ModelRegistry#resolve} 解析 OpenAI 兼容模型
 * （openai:qwen3.7-plus → compatible-mode/v1），非流式单块响应。判分调用记账 llm_usage
 * （agent_type=evaluation，session_id=运行 traceId），驾驶舱 LLM 用量与报告 TraceID 联动。
 *
 * <p>提示词模板占位：{question} 用户提问 / {response} 被测响应 / {reference} 参考答案；
 * 内置 6 个评测器用默认中文模板，自定义 LLM-Judge 用用户配置的 judge_prompt。</p>
 */
@Service
public class LlmJudgeScorer {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeScorer.class);
    private static final Pattern SCORE = Pattern.compile("\\b\\d{1,3}\\b");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 结构化输出约束：score 0-100 + reason 理由（DeepEval 风格），解析容错降级正则。 */
    private static final String JUDGE_SYSTEM = """
            你是评测专家助手。请以 JSON 对象输出判分结果，不要输出任何其他内容：
            {"score": 0-100 的整数分数, "reason": "评分依据与解读"}""";

    /** 内置 6 个 LLM-Judge 默认中文提示词模板。 */
    private static final Map<String, String> DEFAULT_PROMPTS = Map.of(
            "llm_correctness", "对照参考答案，判断被测响应在事实与语义上的正确程度，输出 0-100 整数。100=完全正确，0=完全错误。\n问题：{question}\n被测响应：{response}\n参考答案：{reference}",
            "llm_instruction_following", "判断被测响应在多大程度上遵循了用户指令与约束，输出 0-100 整数。100=完全遵循，0=完全偏离。\n问题：{question}\n被测响应：{response}\n参考答案（期望行为）：{reference}",
            "llm_relevance", "判断被测响应与用户问题的相关程度，输出 0-100 整数。100=完全切题，0=完全无关。\n问题：{question}\n被测响应：{response}\n参考答案：{reference}",
            "llm_hallucination", "判断被测响应相对参考答案的幻觉（无依据编造）程度，输出 0-100 整数。100=无任何幻觉，0=完全幻觉。\n问题：{question}\n被测响应：{response}\n参考答案：{reference}",
            "llm_reasoning_groundedness", "判断被测响应推理过程的依据充分程度（是否忠实于给定上下文与参考答案），输出 0-100 整数。100=推理完全有据，0=凭空推断。\n问题：{question}\n被测响应：{response}\n参考答案：{reference}",
            "llm_response_completeness", "判断被测响应相对参考答案的完整程度（是否覆盖全部要点），输出 0-100 整数。100=完整覆盖，0=严重缺漏。\n问题：{question}\n被测响应：{response}\n参考答案：{reference}");

    private final AgentLlmProperties llm;
    private final LlmUsageService llmUsageService;

    public LlmJudgeScorer(AgentLlmProperties llm, LlmUsageService llmUsageService) {
        this.llm = llm;
        this.llmUsageService = llmUsageService;
    }

    /** 判分轮次（含 reason）。 */
    public record JudgeRound(double score, String reason) {
    }

    /** 多轮判分结果：均值 + 各轮明细（reason 与离散度计算原料）。 */
    public record JudgeDetail(double mean, List<JudgeRound> rounds) {
    }

    /** 内置评测器默认提示词（自定义评测器用用户 judge_prompt，不经此表）。 */
    public static String defaultPrompt(String metric) {
        return DEFAULT_PROMPTS.get(metric);
    }

    /**
     * 判分一轮的均值：judgeRounds 次调模型打分取平均；任一异常/空返回不中断，
     * 全部轮次失败返回 null（调用方降级近似）。兼容既有调用方（同步 run 路径）。
     */
    public Double score(String metric, String promptTemplate, String question, String response,
                        String reference, int rounds, Long tenantId, String sessionId) {
        JudgeDetail detail = judgeDetailed(metric, promptTemplate, question, response,
                reference, rounds, tenantId, sessionId);
        return detail == null ? null : detail.mean();
    }

    /**
     * 结构化判分：返回均值 + 各轮 {@link JudgeRound}（score 与 reason）。LLM 未启用 /
     * 全部轮次失败 → null。异步任务路径经此取样本级 reason 与轮次离散度。
     */
    public JudgeDetail judgeDetailed(String metric, String promptTemplate, String question,
                                     String response, String reference, int rounds,
                                     Long tenantId, String sessionId) {
        if (!llm.isEnabled() || llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            return null;
        }
        String filled = promptTemplate == null || promptTemplate.isBlank()
                ? defaultPrompt(metric) : promptTemplate;
        if (filled == null) {
            log.warn("LLM 判分跳过：评测器 {} 无提示词模板，降级近似", metric);
            return null;
        }
        try {
            Model model = ModelRegistry.resolve(llm.getModelId(), ModelCreationContext.builder()
                    .apiKey(llm.getApiKey())
                    .baseUrl(llm.getBaseUrl())
                    .build());
            List<JudgeRound> roundsList = new ArrayList<>();
            for (int i = 0; i < Math.max(rounds, 1); i++) {
                JudgeRound r = judgeOnce(model, filled, question, response, reference,
                        tenantId, sessionId, metric);
                if (r != null) {
                    roundsList.add(r);
                }
            }
            if (roundsList.isEmpty()) {
                log.warn("LLM 判分全部轮次失败，用例降级近似 (metric={}, rounds={})", metric, rounds);
                return null;
            }
            double mean = roundsList.stream().mapToDouble(JudgeRound::score).average().orElse(0);
            return new JudgeDetail(mean, List.copyOf(roundsList));
        } catch (Exception e) {
            log.warn("LLM 判分失败（provider 不可用/参数非法），降级近似 (metric={}): {}", metric, e.getMessage());
            return null;
        }
    }

    /** 单轮判分：非流式模型调用 → 解析结构化 JSON {score, reason}；失败返回 null。 */
    private JudgeRound judgeOnce(Model model, String prompt, String question, String response,
                                 String reference, Long tenantId, String sessionId, String metric) {
        try {
            String filled = prompt
                    .replace("{question}", question == null ? "" : question)
                    .replace("{response}", response == null ? "" : response)
                    .replace("{reference}", reference == null ? "" : reference);
            List<Msg> msgs = List.of(
                    new SystemMessage(JUDGE_SYSTEM),
                    new UserMessage(filled));
            // 与评测主链路 AgentRunConfig 15s 尝试额度对齐：LLM 判分挂起时快速降级，不无限阻塞当前请求线程
            ChatResponse resp = model.stream(msgs, List.of(),
                    GenerateOptions.builder().stream(false).build())
                    .blockFirst(Duration.ofMillis(15_000));
            if (resp == null) {
                return null;
            }
            String text = textOf(resp.getContent());
            JudgeRound parsed = parseJudgeResult(text);
            if (parsed == null) {
                log.warn("LLM 判分响应不可解析（无 0-100 数字），降级本用例 (metric={}): {}", metric, snippet(text));
                return null;
            }
            recordUsage(resp.getUsage(), tenantId, sessionId);
            return parsed;
        } catch (Exception e) {
            log.warn("LLM 判分单轮失败，降级本用例 (metric={}): {}", metric, e.getMessage());
            return null;
        }
    }

    /**
     * 解析判分响应文本为 {@link JudgeRound}（包内可见供单测）：
     * 优先 JSON 对象（容错 ```json 围栏/前后缀/夹带文字，取 score/reason），
     * 失败降级提取第一个 0-100 数字（reason=原文摘要）；均失败返回 null。
     */
    static JudgeRound parseJudgeResult(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode obj = tryJsonObject(text);
        if (obj != null) {
            JsonNode scoreNode = obj.path("score");
            JsonNode reasonNode = obj.path("reason");
            if (scoreNode.isNumber()) {
                double v = scoreNode.asDouble();
                if (v >= 0 && v <= 100) {
                    return new JudgeRound(v, reasonNode.isTextual() ? reasonNode.asText() : null);
                }
            }
        }
        Double v = parseScore(text);
        return v == null ? null : new JudgeRound(v, snippet(text));
    }

    /** 从响应文本提取 JSON 对象（直接解析 → 去掉 ``` 围栏 → 截取首尾大括号），失败返回 null。 */
    private static JsonNode tryJsonObject(String text) {
        String stripped = stripFence(text).trim();
        try {
            return MAPPER.readTree(stripped);
        } catch (Exception ignored) {
            // 夹带前后缀等非纯 JSON，继续兜底截取
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return MAPPER.readTree(text.substring(start, end + 1));
            } catch (Exception ignored) {
                // 不构成合法 JSON，走数字正则降级
            }
        }
        return null;
    }

    /** 去掉 ```json ``` 围栏（含语言标识），返回围栏内内容。 */
    private static String stripFence(String text) {
        int first = text.indexOf("```");
        if (first < 0) {
            return text;
        }
        int lineStart = text.indexOf('\n', first);
        int start = lineStart < 0 ? first + 3 : lineStart + 1;
        int last = text.lastIndexOf("```");
        if (last > start) {
            return text.substring(start, last);
        }
        return text;
    }

    private void recordUsage(ChatUsage usage, Long tenantId, String sessionId) {
        if (usage == null || tenantId == null) {
            return;
        }
        llmUsageService.recordCall(tenantId, "evaluation", sessionId,
                usage.getInputTokens(), usage.getOutputTokens(), usage.getCachedTokens(), null);
    }

    /** 拼接响应中全部文本块。 */
    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /** 提取第一个 0-100 的整数/小数分数；无 → null。 */
    private static Double parseScore(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SCORE.matcher(text);
        while (m.find()) {
            double v = Double.parseDouble(m.group());
            if (v >= 0 && v <= 100) {
                return v;
            }
        }
        return null;
    }

    private static String snippet(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }
}