package com.easysys.api.dto.template;

import java.time.Instant;

/**
 * 模板视图。
 */
public record TemplateView(
        Long id,
        String channel,
        String name,
        String content,
        String status,
        Instant createdAt) {
}