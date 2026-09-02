package com.easysys.api.dto.retention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 行为事件批量上报请求（幂等：同 (contact, event_name, occurred_at) 重复上报忽略）。
 */
public record EventImportRequest(
        @Valid @NotEmpty List<EventItem> events) {

    public record EventItem(
            @NotNull Long contactId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String eventName,
            @NotNull java.time.Instant occurredAt,
            Object payload) {
    }
}