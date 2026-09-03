package com.easysys.api.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性中文词元化单元测试（无 Spring 上下文）：拉丁/数字整串小写词元、
 * 汉字二元组（奇数末尾退化单字）、标点不产词元、词频聚合、查询去重保序。
 */
class CjkTokenizerTest {

    @Test
    void hanRunsBecomeBigramsWithOddTail() {
        // 4 字：两个二元组，无残字
        assertThat(CjkTokenizer.counts("会员权益"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("会员", 1, "权益", 1));
        // 5 字：两个二元组 + 末尾单字
        assertThat(CjkTokenizer.counts("会员权益和"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("会员", 1, "权益", 1, "和", 1));
    }

    @Test
    void latinDigitRunsLowercasedWhole() {
        assertThat(CjkTokenizer.counts("VIP1888会员"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("vip1888", 1, "会员", 1));
        assertThat(CjkTokenizer.counts("A1b2C3"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a1b2c3", 1));
    }

    @Test
    void punctuationYieldsNoTokensAndSeparatesRuns() {
        // 空格拆分两个汉字串 → 词频聚合为 2
        assertThat(CjkTokenizer.counts("权益 权益")).containsExactlyInAnyOrderEntriesOf(Map.of("权益", 2));
        // 标点不产出词元
        assertThat(CjkTokenizer.counts("会员权益！？。，")).containsOnlyKeys("会员", "权益");
    }

    @Test
    void keysDeduplicatesInFirstSeenOrder() {
        assertThat(CjkTokenizer.keys("会员权益 权益 会员"))
                .isEqualTo(List.of("会员", "权益"));
    }
}