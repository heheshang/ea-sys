package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性评测规划器（规则主实现/兜底）：按评测器目录对每个用例打分、聚合指标均值、
 * 产分级发现与汇总 verdict。输出自洽满足 {@link LayerSchemas#evaluationReportSchema()}。
 *
 * <p>评测器 = 内置常量目录（11 个，代码内置不落表）：
 * <ul>
 *   <li>规则 5：number_accuracy / string_exact / response_repetition / text_similarity /
 *       observation_information_gain —— 纯确定性算法，本类内实现。</li>
 *   <li>LLM-Judge 6：llm_correctness / llm_instruction_following / llm_relevance /
 *       llm_hallucination / llm_reasoning_groundedness / llm_response_completeness ——
 *       LLM 提供方未启用（easysys.agent.llm.enabled=false，默认）时以规则近似降级
 *       （文本相似度/未见内容占比等），保证结果可断言；LLM 启用后由 harness 主链路
 *       （真实模型评估）承接，本类近似分支保留但不作为测试覆盖路径。</li>
 * </ul>
 *
 * <p>入参（EvaluationService 组装）：
 * <pre>
 * {"scope": "llm_call", "mode": "openjudge|execute", "llm_enabled": false,
 *  "cases": [{"seq": N, "question": "...", "system_prompt": "...",
 *             "expected_output": any, "expected_tool": {"name": "...", "args": {...}},
 *             "tool_schema": {...}, "provided_response": "...", "actual_response": "..."}],
 *  "evaluators": [{"metric": "...", "category": "rule|llm_judge"}]}
 * </pre>
 * actual_response 为判分对象：openjudge 模式取 provided_response（跳过执行），
 * execute 模式由 service 先运行被测智能体注入。规则与降级近似全部确定性、无随机、无网络。
 */
public final class EvaluationModel implements StrategyAgent, AgentFallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** 规则评测器（确定性算法，本类实现）。 */
    public static final List<String> RULE_METRICS = List.of(
            "number_accuracy",
            "string_exact",
            "response_repetition",
            "text_similarity",
            "observation_information_gain");

    /** LLM-Judge 评测器（LLM 未启用时确定性近似降级）。 */
    public static final List<String> LLM_JUDGE_METRICS = List.of(
            "llm_correctness",
            "llm_instruction_following",
            "llm_relevance",
            "llm_hallucination",
            "llm_reasoning_groundedness",
            "llm_response_completeness");

    /** 全部 11 个内置评测器。 */
    public static final List<String> ALL_METRICS = concat(RULE_METRICS, LLM_JUDGE_METRICS);

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    @Override
    public AgentType type() {
        return AgentType.EVALUATION;
    }

    @Override
    public String schema() {
        return LayerSchemas.evaluationReportSchema();
    }

    @Override
    public JsonNode plan(JsonNode input) {
        return build(input);
    }

    @Override
    public JsonNode fallback(JsonNode input) {
        return build(input);
    }

    private static JsonNode build(JsonNode input) {
        String scope = input == null ? "llm_call" : input.path("scope").asText("llm_call");
        String mode = input == null ? "openjudge" : input.path("mode").asText("openjudge");
        boolean llmEnabled = input != null && input.path("llm_enabled").asBoolean(false);
        ArrayNode cases = input == null ? MAPPER.createArrayNode() : (ArrayNode) input.path("cases");
        ArrayNode evaluators = input == null ? MAPPER.createArrayNode() : (ArrayNode) input.path("evaluators");

        // 选中的评测器（缺省 = 全量 11 个）
        List<EvaluatorSpec> selected = new ArrayList<>();
        if (evaluators == null || evaluators.isEmpty()) {
            for (String m : ALL_METRICS) {
                selected.add(new EvaluatorSpec(m, categoryOf(m)));
            }
        } else {
            for (JsonNode ev : evaluators) {
                String metric = ev.path("metric").asText("");
                if (!ALL_METRICS.contains(metric)) {
                    continue;
                }
                selected.add(new EvaluatorSpec(metric, categoryOf(metric)));
            }
        }

        // 逐评测器 × 逐用例打分（null = 不适用，不计入均值）
        for (EvaluatorSpec spec : selected) {
            List<Double> scores = new ArrayList<>();
            int applicable = 0;
            for (JsonNode c : cases) {
                Double s = score(spec.metric, c);
                if (s != null) {
                    scores.add(s);
                    applicable++;
                }
            }
            double avg = scores.isEmpty() ? 0.0 : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            int passed = (int) scores.stream().filter(s -> s >= 0.8).count();
            spec.applicable = applicable;
            spec.avg = scores.isEmpty() ? null : avg;
            spec.passed = passed;
        }

        // 指标均值
        ArrayNode metrics = MAPPER.createArrayNode();
        for (EvaluatorSpec spec : selected) {
            if (spec.avg == null) {
                continue;
            }
            ObjectNode m = MAPPER.createObjectNode();
            m.put("metric", spec.metric);
            m.put("category", spec.category);
            m.put("avg_score", round4(spec.avg));
            m.put("passed_count", spec.passed);
            m.put("applicable_count", spec.applicable);
            metrics.add(m);
        }

        // 分级发现
        ArrayNode findings = MAPPER.createArrayNode();
        for (EvaluatorSpec spec : selected) {
            if (spec.avg == null) {
                findings.add(finding("INFO", spec.metric,
                        "评测器 " + spec.metric + " 无适用用例（数据缺失），未纳入均值统计",
                        "补充带 expected 基准的用例后重跑"));
                continue;
            }
            String level;
            String suggestion;
            if (spec.avg < 0.6) {
                level = "BLOCKED";
                suggestion = "优先修复该维度输出质量，追平评测器基线后再发布上线";
            } else if (spec.avg < 0.8) {
                level = "WARNING";
                suggestion = "关注未通过用例（score<0.8），优化提示词/工具调用或补充期望答案";
            } else {
                level = null;
                suggestion = null;
            }
            if (level != null) {
                findings.add(finding(level, spec.metric,
                        "评测器 " + spec.metric + " 均值 " + pct(spec.avg)
                                + "（" + spec.passed + "/" + spec.applicable + " 例通过，阈值 80%）",
                        suggestion));
            }
        }

        // 汇总 verdict
        double raw = metrics.isEmpty()
                ? 0.0
                : selected.stream().filter(s -> s.avg != null).mapToDouble(s -> s.avg).average().orElse(0);
        double summaryScore = Math.round(raw * 1000) / 10.0;
        String verdict = summaryScore >= 80 ? "PASS" : (summaryScore >= 60 ? "WARN" : "FAIL");

        ObjectNode out = MAPPER.createObjectNode();
        out.put("report_type", "evaluation_report");
        out.put("scope", scope);
        out.put("mode", mode);
        out.put("tested_cases", cases.size());
        out.set("metrics", metrics);
        out.set("findings", findings);
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("score", summaryScore);
        summary.put("verdict", verdict);
        out.set("summary", summary);
        out.put("strategy_version", "rule");
        out.put("confidence", 1.0);
        out.put("generated_at", java.time.Instant.now().toString());
        return out;
    }

    private static String categoryOf(String metric) {
        return RULE_METRICS.contains(metric) ? "rule" : "llm_judge";
    }

    /** 单用例单评测器打分；返回 null 表示不适用（缺基准/缺响应）。 */
    static Double score(String metric, JsonNode c) {
        String actual = text(c.path("actual_response"));
        if (actual.isEmpty()) {
            return null;
        }
        String expected = text(c.path("expected_output"));
        String question = text(c.path("question"));
        String systemPrompt = text(c.path("system_prompt"));
        String expectedToolName = c.path("expected_tool").isObject()
                ? c.path("expected_tool").path("name").asText("") : "";
        switch (metric) {
            case "number_accuracy":
                return numberAccuracy(expected, actual);
            case "string_exact":
                return stringExact(expected, actual);
            case "response_repetition":
                return repetitionScore(actual);
            case "text_similarity":
                return jaccard(charBigrams(expected), charBigrams(actual));
            case "observation_information_gain":
                return infoGain(question, systemPrompt, actual);
            case "llm_correctness":
                return jaccard(charBigrams(expected), charBigrams(actual));
            case "llm_instruction_following":
                return instructionFollowing(expected, expectedToolName, actual);
            case "llm_relevance":
                return jaccard(charBigrams(question), charBigrams(actual));
            case "llm_hallucination":
                return hallucinationScore(question, systemPrompt, expected, actual);
            case "llm_reasoning_groundedness":
                return groundedness(expected, actual);
            case "llm_response_completeness":
                return completeness(expected, actual);
            default:
                return null;
        }
    }

    /** number_accuracy：期望/实际数字集合命中率；期望无数字 → 不适用。 */
    private static Double numberAccuracy(String expected, String actual) {
        Set<String> e = numbers(expected);
        if (e.isEmpty()) {
            return null;
        }
        Set<String> a = numbers(actual);
        int hit = 0;
        for (String n : e) {
            if (a.contains(n)) {
                hit++;
            }
        }
        return (double) hit / e.size();
    }

    /** string_exact：trim 后全等 1/0；期望为空 → 不适用。 */
    private static Double stringExact(String expected, String actual) {
        if (expected.trim().isEmpty()) {
            return null;
        }
        return expected.trim().equals(actual.trim()) ? 1.0 : 0.0;
    }

    /** response_repetition：1 - n-gram 重复率（重复越多越低分）；文本过短视为无重复。 */
    private static Double repetitionScore(String actual) {
        List<String> grams = charBigramsList(actual);
        if (grams.size() < 2) {
            return 1.0;
        }
        long unique = new HashSet<>(grams).size();
        return 1.0 - (double) (grams.size() - unique) / grams.size();
    }

    /** observation_information_gain：实际响应中超出 question+system_prompt 的新信息比例。 */
    private static Double infoGain(String question, String systemPrompt, String actual) {
        Set<String> context = union(charBigrams(question), charBigrams(systemPrompt));
        Set<String> actualGrams = charBigrams(actual);
        if (actualGrams.isEmpty()) {
            return null;
        }
        if (context.isEmpty()) {
            return 1.0;
        }
        long novel = actualGrams.stream().filter(g -> !context.contains(g)).count();
        return (double) novel / actualGrams.size();
    }

    /** llm_instruction_following（降级近似）：期望要素（工具名 + 期望输出 JSON 顶层 key）在响应中的覆盖比例。 */
    private static Double instructionFollowing(String expected, String expectedToolName, String actual) {
        Set<String> elements = new LinkedHashSet<>();
        if (!expectedToolName.isBlank()) {
            elements.add(expectedToolName);
        }
        try {
            JsonNode exp = MAPPER.readTree(expected);
            if (exp.isObject()) {
                exp.fieldNames().forEachRemaining(elements::add);
            }
        } catch (Exception ignored) {
            // expected 非 JSON：整体作为一个要素
            elements.add(expected.trim());
        }
        if (elements.isEmpty()) {
            return null;
        }
        long matched = elements.stream().filter(el -> actual.contains(el)).count();
        return (double) matched / elements.size();
    }

    /** llm_hallucination（降级近似）：1 - 无依据内容占比；依据 = question+system_prompt+expected。 */
    private static Double hallucinationScore(String question, String systemPrompt, String expected, String actual) {
        Set<String> basis = union(union(charBigrams(question), charBigrams(systemPrompt)), charBigrams(expected));
        Set<String> actualGrams = charBigrams(actual);
        if (actualGrams.isEmpty()) {
            return null;
        }
        if (basis.isEmpty()) {
            return null;
        }
        long unsupported = actualGrams.stream().filter(g -> !basis.contains(g)).count();
        return 1.0 - (double) unsupported / actualGrams.size();
    }

    /** llm_reasoning_groundedness（降级近似）：实际响应相对期望答案的依据度（bigram 覆盖比例）。 */
    private static Double groundedness(String expected, String actual) {
        Set<String> e = charBigrams(expected);
        Set<String> a = charBigrams(actual);
        if (a.isEmpty() || e.isEmpty()) {
            return null;
        }
        long grounded = a.stream().filter(e::contains).count();
        return (double) grounded / a.size();
    }

    /** llm_response_completeness（降级近似）：响应长度相对期望的覆盖度。 */
    private static Double completeness(String expected, String actual) {
        int lenE = expected.replaceAll("\\s+", "").length();
        if (lenE == 0) {
            return null;
        }
        int lenA = actual.replaceAll("\\s+", "").length();
        return Math.min(1.0, (double) lenA / lenE);
    }

    private static ObjectNode finding(String level, String dimension, String detail, String suggestion) {
        ObjectNode f = MAPPER.createObjectNode();
        f.put("level", level);
        f.put("dimension", dimension);
        f.put("detail", detail);
        if (suggestion != null) {
            f.put("suggestion", suggestion);
        }
        return f;
    }

    // ---- 文本工具 ----

    /** 任意节点 → 判分文本（对象/数组做紧凑 JSON 字符串化）。 */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    static Set<String> numbers(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 字符级 bigram 集合（去空白；单个字符退化为单字符集合；空 → 空集）。 */
    static Set<String> charBigrams(String s) {
        return new HashSet<>(charBigramsList(s));
    }

    static List<String> charBigramsList(String s) {
        String clean = s.replaceAll("\\s+", "");
        List<String> grams = new ArrayList<>();
        if (clean.isEmpty()) {
            return grams;
        }
        if (clean.length() == 1) {
            grams.add(clean);
            return grams;
        }
        for (int i = 0; i < clean.length() - 1; i++) {
            grams.add(clean.substring(i, i + 2));
        }
        return grams;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static final class EvaluatorSpec {
        final String metric;
        final String category;
        Double avg;
        int passed;
        int applicable;
        EvaluatorSpec(String metric, String category) {
            this.metric = metric;
            this.category = category;
            this.avg = null;
            this.passed = 0;
            this.applicable = 0;
        }
    }
}