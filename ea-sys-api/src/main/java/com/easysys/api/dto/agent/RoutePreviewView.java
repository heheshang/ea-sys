package com.easysys.api.dto.agent;

import java.util.List;

/** 路由预览结果：近 24h 触达渠道 + TouchReorder 重排后的通道顺序。 */
public record RoutePreviewView(
        Long contactId,
        List<String> touched,
        List<String> reordered,
        boolean unchanged) {
}