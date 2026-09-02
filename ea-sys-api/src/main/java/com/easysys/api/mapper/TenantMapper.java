package com.easysys.api.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 租户表在 MybatisPlusConfig IGNORE_TABLES 中，直插无需租户上下文/InterceptorIgnore。 */
public interface TenantMapper {

    @Insert("INSERT INTO tenant (id, name) VALUES (#{id}, #{name}) ON CONFLICT (id) DO NOTHING")
    int insertIgnore(@Param("id") Long id, @Param("name") String name);
}