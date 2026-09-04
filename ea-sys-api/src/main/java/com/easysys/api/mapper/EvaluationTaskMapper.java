package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.EvaluationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 评测任务状态机（H1）。异步执行线程无请求租户上下文，故全部自定义 DML 绕过租户插件、
 * 显式携带 tenant_id 过滤（与 LlmUsageMapper 同款租户策略）。状态迁移全部带状态前置条件，
 * 使「取消」与「执行完成」竞争时由数据库原子裁决。
 */
public interface EvaluationTaskMapper extends BaseMapper<EvaluationTask> {

    /** PENDING→RUNNING：抢占任务（取消已抢先置 CANCELED 时返回 0，执行线程直接退出）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET status = 'RUNNING', total_cases = #{totalCases}, updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PENDING' AND deleted = FALSE
            """)
    int claimRunning(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("totalCases") Integer totalCases);

    /** 逐样本进度上报（每 5 例一次）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET tested_cases = #{testedCases}, progress_pct = #{progressPct}, updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'RUNNING' AND deleted = FALSE
            """)
    int markProgress(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("testedCases") Integer testedCases,
                     @Param("progressPct") BigDecimal progressPct);

    /** RUNNING→CANCELING：取消请求落地，执行线程在逐样本检查点看到后置 CANCELED。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET status = 'CANCELING', updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'RUNNING' AND deleted = FALSE
            """)
    int markCanceling(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 终态 CANCELED（PENDING 直接取消 / RUNNING/CANCELING 由执行线程取消）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET status = 'CANCELED', error_message = #{errorMessage}, report_id = NULL, updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('PENDING', 'RUNNING', 'CANCELING')
              AND deleted = FALSE
            """)
    int markCanceled(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("errorMessage") String errorMessage);

    /** RUNNING→COMPLETED：报告落库后统一收尾；已被取消（CANCELING）时返回 0 由调用方回滚报告。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET status = 'COMPLETED', tested_cases = total_cases, progress_pct = 100,
                report_id = #{reportId}, sample_results = CAST(#{sampleResults} AS JSONB), updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'RUNNING' AND deleted = FALSE
            """)
    int markCompleted(@Param("id") Long id, @Param("tenantId") Long tenantId,
                      @Param("reportId") Long reportId, @Param("sampleResults") String sampleResults);

    /** 任务级失败（数据集停用/无用例/未知模式/内部异常）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE evaluation_task
            SET status = 'FAILED', error_message = #{errorMessage}, updated_at = now()
            WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('PENDING', 'RUNNING')
              AND deleted = FALSE
            """)
    int markFailed(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("errorMessage") String errorMessage);
}