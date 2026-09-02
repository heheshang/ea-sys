package com.easysys.api.dto.agent;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 路由预览请求：小样本触达史重排（近 24h 已触达渠道后置）。 */
public record RoutePreviewRequest(
        @NotNull Long contactId,
        List<String> routeOrder) {
}