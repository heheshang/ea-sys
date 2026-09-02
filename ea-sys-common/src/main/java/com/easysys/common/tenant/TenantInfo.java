package com.easysys.common.tenant;

/**
 * 当前请求的租户信息，由 JWT 解析后写入。
 */
public record TenantInfo(Long tenantId) {
}