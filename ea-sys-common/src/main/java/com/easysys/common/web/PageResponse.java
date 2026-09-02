package com.easysys.common.web;

import java.util.List;

/**
 * 分页响应体。
 */
public record PageResponse<T>(List<T> records, long total, long page, long size) {

    public static <T> PageResponse<T> of(List<T> records, long total, long page, long size) {
        return new PageResponse<>(records, total, page, size);
    }
}