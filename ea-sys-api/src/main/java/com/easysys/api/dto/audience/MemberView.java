package com.easysys.api.dto.audience;

/**
 * 快照成员预览行（JOIN contact 取展示字段）。
 */
public record MemberView(
        Long contactId,
        String externalId,
        String phone,
        String email,
        String status) {
}