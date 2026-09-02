package com.easysys.api.auth;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 除公开路径外的请求必须携带合法 Bearer Token；
 * 解析出的 tid 写入租户上下文，uid/username/role 置为请求属性。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/health");

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "缺少 Authorization: Bearer <token>");
            return;
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(7));
            TenantContext.set(new TenantInfo(claims.get("tid", Long.class)));
            request.setAttribute("uid", claims.get("uid", Number.class).longValue());
            request.setAttribute("username", claims.getSubject());
            request.setAttribute("role", claims.get("role", String.class));
            chain.doFilter(request, response);
        } catch (Exception e) {
            writeUnauthorized(response, "Token 无效或已过期");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40100,\"message\":\"" + message + "\"}");
    }
}