package com.easysys.api.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.entity.SysUser;
import com.easysys.api.mapper.SysUserMapper;
import com.easysys.api.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 仅 dev profile：初始化演示租户与 admin 账号（admin / admin123）。
 */
@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final TenantMapper tenantMapper;
    private final SysUserMapper userMapper;

    public DevDataInitializer(TenantMapper tenantMapper, SysUserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenantMapper.insertIgnore(1L, "dev-tenant");
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin").last("limit 1"));
        if (admin == null) {
            SysUser user = new SysUser();
            user.setTenantId(1L);
            user.setUsername("admin");
            user.setRole("admin");
            user.setStatus("active");
            user.setPasswordHash(new BCryptPasswordEncoder().encode("admin123"));
            userMapper.insert(user);
            log.info("dev 数据已初始化：租户 1 / admin / admin123");
        }
    }
}