package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.Contact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface ContactMapper extends BaseMapper<Contact> {

    /** 测试辅助：跨租户直插联系人（绕过租户插件，tenant_id 显式指定），供租户隔离断言。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO contact (tenant_id, external_id, phone, status) " +
            "VALUES (#{tenantId}, #{externalId}, #{phone}, 'active')")
    int testInsertRawContact(@Param("tenantId") Long tenantId,
                             @Param("externalId") String externalId,
                             @Param("phone") String phone);
}