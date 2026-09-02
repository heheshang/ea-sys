package com.easysys.api.dto.audience;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 接触对象创建/更新请求。属性与标签为全量替换语义。
 */
public record ContactRequest(
        @Size(max = 128) String externalId,
        @Pattern(regexp = "^$|^[0-9+\\- ]{5,32}$", message = "手机号格式不正确") String phone,
        @Email @Size(max = 256) String email,
        @Size(max = 256) String pushToken,
        @Pattern(regexp = "^(active|silent|unsubscribed)$", message = "status 仅支持 active/silent/unsubscribed") String status,
        Map<String, Object> attributes,
        List<@Size(max = 64) String> tags) {
}