package com.easysys.api.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试辅助：种子隔离租户行（tenant 表受租户插件豁免，FK 供 channel_config 引用）。 */
@Mapper
public interface TenantSeedMapper {

    @Insert("INSERT INTO tenant (id, name) VALUES (#{id}, #{name}) ON CONFLICT (id) DO NOTHING")
    void seedTenant(@Param("id") long id, @Param("name") String name);
}