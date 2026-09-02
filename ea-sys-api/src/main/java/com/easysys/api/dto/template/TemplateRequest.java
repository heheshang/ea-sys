package com.easysys.api.dto.template;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 模板保存请求（tenant 级，FreeMarker 渲染；channel 决定路由适配器）。
 */
public record TemplateRequest(
        @NotBlank String channel,
        @NotBlank @Size(max = 128) String name,
        @NotBlank String content) {
}