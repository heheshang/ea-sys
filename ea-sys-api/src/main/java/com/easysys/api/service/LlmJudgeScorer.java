package com.easysys.api.service;

import com.easysys.api.config.AgentLlmProperties;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 真实判分器：LLM 主提供方启用（easysys.agent.llm enabled + apiKey）时，对选中
 * LLM-Judge 评测器逐 case 调 Judge 模型打分（0-100），judgeRounds 次取均值；判分失败/
 * 超时/空返回 → 返回 null，由调用方降级为确定性近似（不整轮失败）。
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
    private static final String JUDGE_SYSTEM = "你是评测专家助手。只输出一个 0-100 的整数分数，不要任何其他内容。";

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

    /** 内置评测器默认提示词（自定义评测器用用户 judge_prompt，不经此表）。 */
    public static String defaultPrompt(String metric) {
        return DEFAULT_PROMPTS.get(metric);
    }

    /**
     * 判分一轮的均值：judgeRounds 次调模型打分（0-100）取平均；任一异常/空返回不中断，
     * 全部轮次失败返回 null（调用方降级近似）。
     */
    public Double score(String metric, String promptTemplate, String question, String response,
                        String reference, int rounds, Long tenantId, String sessionId) {
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
            double sum = 0;
            int ok = 0;
            for (int i = 0; i < Math.max(rounds, 1); i++) {
                Double s = judgeOnce(model, filled, question, response, reference, tenantId, sessionId, metric);
                if (s != null) {
                    sum += s;
                    ok++;
                }
            }
            if (ok == 0) {
                log.warn("LLM 判分全部轮次失败，用例降级近似 (metric={}, rounds={})", metric, rounds);
                return null;
            }
            return sum / ok;
        } catch (Exception e) {
            log.warn("LLM 判分失败（provider 不可用/参数非法），降级近似 (metric={}): {}", metric, e.getMessage());
            return null;
        }
    }

    /** 单轮判分：非流式模型调用 → 提取 0-100 数字；失败返回 null。 */
    private Double judgeOnce(Model model, String prompt, String question, String response,
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
            Double parsed = parseScore(text);
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