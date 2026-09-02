package com.easysys.api.controller;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 受保护接口：返回当前 Token 对应的租户与用户，验证 JWT → 租户上下文链路。
 */
@RestController
public class WhoamiController {

    @GetMapping("/api/whoami")
    public ApiResponse<Map<String, Object>> whoami(HttpServletRequest request) {
        return ApiResponse.ok(Map.of(
                "tenantId", TenantContext.tenantId(),
                "userId", request.getAttribute("uid"),
                "username", request.getAttribute("username"),
                "role", request.getAttribute("role")));
    }
}