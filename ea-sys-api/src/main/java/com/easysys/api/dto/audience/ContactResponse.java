package com.easysys.api.dto.audience;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ContactResponse(
        Long id,
        String externalId,
        String phone,
        String email,
        String pushToken,
        String wechatOpenid,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> attributes,
        List<String> tags) {
}