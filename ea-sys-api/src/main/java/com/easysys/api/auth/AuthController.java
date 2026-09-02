package com.easysys.api.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.entity.SysUser;
import com.easysys.api.mapper.SysUserMapper;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    private final SysUserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(SysUserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest body) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, body.username())
                        .last("limit 1"));
        if (user == null || !encoder.matches(body.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = jwtUtil.sign(user.getTenantId(), user.getId(), user.getUsername(), user.getRole());
        return ApiResponse.ok(Map.of(
                "token", token,
                "username", user.getUsername(),
                "role", user.getRole(),
                "tenantId", user.getTenantId()));
    }
}