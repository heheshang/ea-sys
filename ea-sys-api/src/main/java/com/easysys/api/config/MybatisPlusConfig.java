package com.easysys.api.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.easysys.common.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 租户插件：业务表自动追加 tenant_id 条件。
 * tenant / sys_user 豁免：认证发生在租户上下文建立之前。
 */
@Configuration
public class MybatisPlusConfig {

    // audience_snapshot_member 无 tenant_id 列：隔离经 snapshot(tenant_id) 间接保证
    private static final Set<String> IGNORE_TABLES = Set.of("tenant", "sys_user", "audience_snapshot_member");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tid = TenantContext.tenantId();
                if (tid == null) {
                    throw new IllegalStateException("租户上下文缺失：请求未携带租户信息");
                }
                return new LongValue(tid);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return IGNORE_TABLES.contains(tableName);
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}