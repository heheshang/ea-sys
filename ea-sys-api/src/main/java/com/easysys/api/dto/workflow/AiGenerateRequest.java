package com.easysys.api.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 创建工作流请求：一句自然语言运营需求。
 */
public record AiGenerateRequest(
        @NotBlank(message = "需求描述不能为空")
        @Size(max = 2000, message = "需求描述不能超过 2000 字")
        String prompt) {
}