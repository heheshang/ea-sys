package com.easysys.api.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmJudgeScorer 结构化判分响应解析单测（H2）：纯 JUnit，无 Spring/容器。
 * parseJudgeResult 为包内静态方法，同包直接调用。
 *
 * <p>契约：优先 JSON 对象（容错 ```json 围栏/前后缀/夹带文字，score 0-100 校验），
 * 失败降级提取第一个 0-100 数字（reason=原文摘要），均失败返回 null。</p>
 */
class LlmJudgeScorerParseTest {

    @Test
    void parsesFencedJson() {
        LlmJudgeScorer.JudgeRound r = LlmJudgeScorer.parseJudgeResult(
                "```json\n{\"score\": 85, \"reason\": \"回答完整，覆盖全部要点\"}\n```");
        assertThat(r).isNotNull();
        assertThat(r.score()).isEqualTo(85.0);
        assertThat(r.reason()).isEqualTo("回答完整，覆盖全部要点");
    }

    @Test
    void parsesJsonWithSurroundingText() {
        LlmJudgeScorer.JudgeRound r = LlmJudgeScorer.parseJudgeResult(
                "好的，我的评判是： {\"score\": 90, \"reason\": \"推理有据\"} 希望有帮助。");
        assertThat(r).isNotNull();
        assertThat(r.score()).isEqualTo(90.0);
        assertThat(r.reason()).isEqualTo("推理有据");
    }

    @Test
    void parsesDecimalScoreInRange() {
        LlmJudgeScorer.JudgeRound r = LlmJudgeScorer.parseJudgeResult(
                "{\"score\": 66.7, \"reason\": \"部分正确\"}");
        assertThat(r).isNotNull();
        assertThat(r.score()).isCloseTo(66.7, org.assertj.core.api.Assertions.within(0.001));
        assertThat(r.reason()).isEqualTo("部分正确");
    }

    @Test
    void jsonWithoutReasonKeepsNullReason() {
        LlmJudgeScorer.JudgeRound r = LlmJudgeScorer.parseJudgeResult("{\"score\": 88}");
        assertThat(r).isNotNull();
        assertThat(r.score()).isEqualTo(88.0);
        assertThat(r.reason()).isNull();
    }

    @Test
    void plainNumberDegradesToScoreWithSnippetReason() {
        LlmJudgeScorer.JudgeRound r = LlmJudgeScorer.parseJudgeResult("85");
        assertThat(r).isNotNull();
        assertThat(r.score()).isEqualTo(85.0);
        assertThat(r.reason()).isNotNull();
    }

    @Test
    void outOfRangeScoreFallsBackToRegexAndRejects() {
        // JSON score=150 越界；正则取到的 150 同样越界 0-100 → 无合法分数 → null
        assertThat(LlmJudgeScorer.parseJudgeResult("{\"score\": 150, \"reason\": \"偏高\"}"))
                .isNull();
    }

    @Test
    void missingScoreKeyRejects() {
        assertThat(LlmJudgeScorer.parseJudgeResult("{\"reason\": \"无分数\"}")).isNull();
    }

    @Test
    void garbageWithoutNumberRejects() {
        assertThat(LlmJudgeScorer.parseJudgeResult("完全无法解析的文本")).isNull();
    }

    @Test
    void blankAndNullReject() {
        assertThat(LlmJudgeScorer.parseJudgeResult("   ")).isNull();
        assertThat(LlmJudgeScorer.parseJudgeResult(null)).isNull();
    }
}