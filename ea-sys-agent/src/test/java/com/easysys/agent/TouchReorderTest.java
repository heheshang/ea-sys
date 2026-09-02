package com.easysys.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ROUTER 确定性重排：24h 已触达通道后置、未触达保序前置。
 */
class TouchReorderTest {

    @Test
    void touchedChannelMovesToEnd() {
        assertEquals(List.of("email", "sms"), TouchReorder.reorder(List.of("sms", "email"), List.of("sms")));
    }

    @Test
    void untouchedKeepsOriginalOrder() {
        assertEquals(List.of("sms", "email"), TouchReorder.reorder(List.of("sms", "email"), List.of("email")));
    }

    @Test
    void allTouchedKeepsOrder() {
        assertEquals(List.of("sms", "email"), TouchReorder.reorder(List.of("sms", "email"), List.of("sms", "email")));
    }

    @Test
    void noTouchHistoryKeepsOrder() {
        assertEquals(List.of("sms", "email"), TouchReorder.reorder(List.of("sms", "email"), List.of()));
        assertEquals(List.of("sms", "email"), TouchReorder.reorder(List.of("sms", "email"), null));
    }

    @Test
    void emptyOrderStaysEmpty() {
        assertEquals(List.of(), TouchReorder.reorder(List.of(), List.of("sms")));
    }
}