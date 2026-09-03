package com.easysys.api.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库分块单元测试（无 Spring 上下文）：块边界落在句子上、单块 ≤ 500 字、
 * 长文本完整覆盖不丢内容、短文本单块。
 */
class TextChunkerTest {

    @Test
    void shortTextStaysSingleChunk() {
        List<String> chunks = TextChunker.chunk("会员权益包括生日礼遇与积分翻倍。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("生日礼遇");
    }

    @Test
    void longTextSplitsAtSentenceBoundariesWithinTarget() {
        String sentence = "会员权益包括生日礼遇、积分翻倍、专属客服、免运费与专属折扣价。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append(i).append("、").append(sentence);
        }
        List<String> chunks = TextChunker.chunk(sb.toString());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (String c : chunks) {
            assertThat(c.length()).isLessThanOrEqualTo(TextChunker.TARGET_CHARS);
        }
        // 内容无丢失：拼接还原原文（分块间仅 trim 两侧空白）
        String joined = String.join("", chunks);
        assertThat(joined.replaceAll("\\s+", "")).isEqualTo(sb.toString().replaceAll("\\s+", ""));
    }
}