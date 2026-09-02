package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.ValidationReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 计划校验报告：按工作流查最新一条（发布闸门与回看共用）。
 * 最新 = created_at 倒序首行；租户隔离由租户插件按行过滤。
 */
@Mapper
public interface ValidationReportMapper extends BaseMapper<ValidationReport> {

    /** 最近一条校验报告（无 → null）。 */
    @Select("SELECT * FROM validation_report WHERE tenant_id = #{tenantId} AND workflow_id = #{workflowId} " +
            "AND deleted = FALSE ORDER BY created_at DESC, id DESC LIMIT 1")
    ValidationReport selectLatest(@Param("tenantId") Long tenantId, @Param("workflowId") Long workflowId);

    /** 跨租户查最新一条（发布闸门用：租户上下文缺失的兜底；正常路径走租户插件过滤）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM validation_report WHERE workflow_id = #{workflowId} AND deleted = FALSE " +
            "ORDER BY created_at DESC, id DESC LIMIT 1")
    ValidationReport selectLatestAnyTenant(@Param("workflowId") Long workflowId);
}