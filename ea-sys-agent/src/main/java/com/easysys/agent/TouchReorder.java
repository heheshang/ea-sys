package com.easysys.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 路由 Agent 确定性重排规则（ROUTER fallback）：近 24h 已触达通道后置，
 * 未触达通道保持 DAG 原序在前；全部触达则保持原序（频率闸门在 ACTION 侧再兜底）。
 * 纯函数，供路由预览服务与单元测试直接使用。
 */
public final class TouchReorder {

    private TouchReorder() {
    }

    public static List<String> reorder(List<String> routeOrder, List<String> touchedChannels) {
        if (routeOrder == null || routeOrder.isEmpty()) {
            return List.of();
        }
        Set<String> touched = new LinkedHashSet<>(touchedChannels == null ? List.of() : touchedChannels);
        List<String> out = new ArrayList<>(routeOrder.size());
        for (String channel : routeOrder) {
            if (!touched.contains(channel)) {
                out.add(channel);
            }
        }
        for (String channel : routeOrder) {
            if (touched.contains(channel)) {
                out.add(channel);
            }
        }
        return out;
    }
}