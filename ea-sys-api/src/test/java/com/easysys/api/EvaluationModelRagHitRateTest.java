package com.easysys.api;

import com.easysys.agent.EvaluationModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * rag_hit_rate 纯单元测试（V17，无 Spring/容器）：直接经 EvaluationModel.plan 调
 * 确定性判分器，覆盖期望知识片段 × search_kb 实际工具结果的 bigram 重叠判中边界。
 *
 * <p>输入形状与 EvaluationService execute 链路一致：cases[].expected_kb_hits（字符串数组）
 * + cases[].actual_tool_results[]（name/state/output，output 为 KbSearchView JSON 文本）。
 */
class EvaluationModelRagHitRateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 目录注册 ----------

    @Test
    void ragHitRateRegisteredInRuleMetrics() {
        assertThat(EvaluationModel.RULE_METRICS).contains("rag_hit_rate");
        // P0-P4 新增 decision_accuracy 后规则评测器共 11 条，全量 17 条
        assertThat(EvaluationModel.RULE_METRICS).hasSize(11);
        assertThat(EvaluationModel.ALL_METRICS).hasSize(17);
    }

    // ---------- 命中/未命中/边界 ----------

    @Test
    void fullHitScoresOne() {
        JsonNode out = plan(caseNode(
                List.of("会员权益包括", "积分翻倍"),
                searchKb("会员权益说明.md", "会员权益包括：生日礼遇、积分翻倍、专属客服、免运费。新会员注册后 7 天内可领取新人礼包。")));
        assertThat(avg(out)).isCloseTo(1.0, within(0.001));
        assertThat(applicable(out)).isEqualTo(1);
    }

    @Test
    void partialOverlapScoresHalf() {
        // 片段 1 命中；片段 2（ZZZZZ）零交集 → 1/2 = 0.5
        JsonNode out = plan(caseNode(
                List.of("会员权益包括", "ZZZZZ"),
                searchKb("会员权益说明.md", "会员权益包括：生日礼遇、积分翻倍。")));
        assertThat(avg(out)).isCloseTo(0.5, within(0.001));
        assertThat(applicable(out)).isEqualTo(1);
    }

    @Test
    void noOverlapScoresZero() {
        JsonNode out = plan(caseNode(List.of("ZZZZZ"),
                searchKb("会员权益说明.md", "会员权益包括：生日礼遇、积分翻倍。")));
        assertThat(avg(out)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void exactHalfOverlapCountsAsHit() {
        // 片段 ABCDE → bigram {AB,BC,CD,DE}；命中文本 ABxCDx → {AB,Bx,xC,CD,Dx}：交集 {AB,CD} = 2/4 = 0.5（≥0.5 判命中）
        JsonNode out = plan(caseNode(List.of("ABCDE"), searchKb("d.md", "ABxCDx")));
        assertThat(avg(out)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void singleCharFragmentJudgedByBigramOverlap() {
        // normalize 后长度 1 → 单字符集（与既有 charBigrams 单字符惯例一致）；
        // 多字命中文本的 bigram 集不含单字符 → 单字符片段判分但不命中（计入分母）
        JsonNode out = plan(caseNode(List.of("会员权益包括", "人"),
                searchKb("会员权益说明.md", "新会员注册后 7 天内可领取新人礼包。")));
        assertThat(avg(out)).isCloseTo(0.5, within(0.001));
        assertThat(applicable(out)).isEqualTo(1);
    }

    @Test
    void punctuationAndWhitespaceNormalized() {
        // 期望片段带逗号/空格，命中文本带冒号/顿号：normalize 后 bigram 全等 → 1.0
        JsonNode out = plan(caseNode(List.of("会员权益包括 ， 积分翻倍"),
                searchKb("会员权益说明.md", "会员权益包括：积分翻倍，专属客服。")));
        assertThat(avg(out)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void blankAndPunctuationOnlyFragmentsSkippedFromDenominator() {
        // "" 与 "。。！" normalize 后为空 → 跳过不进分母；仅 1 个可判片段且命中 → 1.0
        JsonNode out = plan(caseNode(List.of("", "。。！", "会员权益包括"),
                searchKb("会员权益说明.md", "会员权益包括：生日礼遇、积分翻倍。")));
        assertThat(avg(out)).isCloseTo(1.0, within(0.001));
        assertThat(applicable(out)).isEqualTo(1);
    }

    @Test
    void nonTextualExpectedFragmentsSkipped() {
        // 数字片段非 textual → 跳过不进分母；仅文本片段判分
        ObjectNode c = MAPPER.createObjectNode();
        c.put("seq", 1);
        c.put("question", "会员权益包括什么？");
        c.putArray("expected_kb_hits").add(123).add("会员权益包括");
        c.putArray("actual_tool_results").add(searchKb("会员权益说明.md", "会员权益包括：积分翻倍。"));
        JsonNode out = plan(c);
        assertThat(avg(out)).isCloseTo(1.0, within(0.001));
    }

    // ---------- 不适用（null → INFO 发现，不报错） ----------

    @Test
    void noSearchKbCallNotApplicableEvenWithExpected() {
        // openjudge 形状：provided_response 有值、无 actual_tool_results → rag_hit_rate 不适用（INFO）
        JsonNode out = plan(caseNode(List.of("会员权益包括")));
        assertThat(avg(out)).isEqualTo(-1);
        assertThat(infoFinding(out)).isTrue();
    }

    @Test
    void emptyExpectedKbHitsNotApplicable() {
        JsonNode out = plan(caseNode(List.of(),
                searchKb("会员权益说明.md", "会员权益包括：积分翻倍。")));
        assertThat(avg(out)).isEqualTo(-1);
        assertThat(infoFinding(out)).isTrue();
    }

    @Test
    void emptyHitsNotApplicable() {
        // search_kb 命中为空 → 无命中文本 → 不适用
        ObjectNode r = searchKbResult(node -> {
            ObjectNode view = MAPPER.createObjectNode();
            view.put("query", "q");
            view.putArray("hits");
            view.put("note", "暂未找到");
            return view;
        });
        JsonNode out = plan(caseNode(List.of("会员权益包括"), r));
        assertThat(avg(out)).isEqualTo(-1);
        assertThat(infoFinding(out)).isTrue();
    }

    @Test
    void nonSearchKbToolsIgnored() {
        ObjectNode stats = MAPPER.createObjectNode();
        stats.put("name", "query_stats");
        stats.put("state", "success");
        stats.put("output", "{\"topic\":\"retention\"}");
        JsonNode out = plan(caseNode(List.of("会员权益包括"), stats));
        assertThat(avg(out)).isEqualTo(-1);
        assertThat(infoFinding(out)).isTrue();
    }

    @Test
    void nonJsonOutputSkipped() {
        // search_kb 出错：output 为 "Error: ..." 纯文本 → 解析失败跳过 → 不适用
        ObjectNode r = MAPPER.createObjectNode();
        r.put("name", "search_kb");
        r.put("state", "error");
        r.put("output", "Error: 知识库检索失败");
        JsonNode out = plan(caseNode(List.of("会员权益包括"), r));
        assertThat(avg(out)).isEqualTo(-1);
        assertThat(infoFinding(out)).isTrue();
    }

    // ---------- helpers ----------

    private static JsonNode plan(ObjectNode c) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("scope", "llm_call");
        input.put("mode", "execute");
        input.put("llm_enabled", false);
        input.put("judge_rounds", 1);
        ArrayNode cases = input.putArray("cases");
        cases.add(c);
        ArrayNode evals = input.putArray("evaluators");
        evals.addObject().put("metric", "rag_hit_rate").put("category", "rule");
        return new EvaluationModel().plan(input);
    }

    private static double avg(JsonNode out) {
        for (JsonNode m : out.path("metrics")) {
            if ("rag_hit_rate".equals(m.path("metric").asText())) {
                return m.path("avg_score").asDouble(-1);
            }
        }
        return -1;
    }

    private static int applicable(JsonNode out) {
        for (JsonNode m : out.path("metrics")) {
            if ("rag_hit_rate".equals(m.path("metric").asText())) {
                return m.path("applicable_count").asInt(-1);
            }
        }
        return -1;
    }

    private static boolean infoFinding(JsonNode out) {
        for (JsonNode f : out.path("findings")) {
            if ("INFO".equals(f.path("level").asText())
                    && "rag_hit_rate".equals(f.path("dimension").asText())) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode caseNode(List<String> expectedKbHits, ObjectNode... toolResults) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("seq", 1);
        c.put("question", "会员权益包括什么？");
        ArrayNode expected = c.putArray("expected_kb_hits");
        expectedKbHits.forEach(expected::add);
        if (toolResults.length > 0) {
            ArrayNode results = c.putArray("actual_tool_results");
            for (ObjectNode r : toolResults) {
                results.add(r);
            }
        }
        return c;
    }

    /** search_kb 工具结果：output = KbSearchView JSON 文本（hits 含 documentName/content）。 */
    private static ObjectNode searchKb(String documentName, String content) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("name", "search_kb");
        r.put("state", "success");
        r.put("output", searchKbView(documentName, content));
        return r;
    }

    /** 自定义 output JSON（如 hits 为空场景）。 */
    private static ObjectNode searchKbResult(java.util.function.Function<ObjectNode, ObjectNode> viewBuilder) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("name", "search_kb");
        r.put("state", "success");
        r.put("output", viewBuilder.apply(MAPPER.createObjectNode()).toString());
        return r;
    }

    private static String searchKbView(String documentName, String content) {
        ObjectNode view = MAPPER.createObjectNode();
        view.put("query", "会员权益包括什么？");
        ArrayNode hits = view.putArray("hits");
        ObjectNode h = hits.addObject();
        h.put("documentId", 1);
        h.put("documentName", documentName);
        h.put("seq", 1);
        h.put("content", content);
        h.put("score", 1.0);
        view.put("note", "");
        return view.toString();
    }
}