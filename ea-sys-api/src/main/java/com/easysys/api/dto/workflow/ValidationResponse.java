package com.easysys.api.dto.workflow;

import java.util.List;

/**
 * 画布结构校验结果。valid=true 表示 errors 为空（可发布）。
 */
public record ValidationResponse(boolean valid, List<String> errors) {

    public static ValidationResponse of(List<String> errors) {
        return new ValidationResponse(errors == null || errors.isEmpty(), errors == null ? List.of() : errors);
    }
}