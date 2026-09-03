package com.easysys.api.dto.assistant;

import java.util.List;

/**
 * 知识库检索结果：命中列表 + 未命中/空库提示文案（策略器据此组装引用式回答；
 * 工具结果 JSON 与 SSE 卡片共用此形状）。
 */
public record KbSearchView(
        String query,
        List<KbHit> hits,
        String note) {
}