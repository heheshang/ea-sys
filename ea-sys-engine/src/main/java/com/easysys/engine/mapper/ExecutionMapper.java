package com.easysys.engine.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.engine.entity.Execution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ExecutionMapper extends BaseMapper<Execution> {

    /** 非 dry-run 执行引用的快照 id（可限定工作流；null = 租户全量）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT DISTINCT e.audience_snapshot_id FROM execution e
            WHERE e.tenant_id = #{tenantId} AND e.dry_run = false AND e.audience_snapshot_id IS NOT NULL
            <if test="workflowId != null">AND e.workflow_id = #{workflowId}</if>
            </script>
            """)
    List<Long> selectAudienceSnapshotIds(@Param("tenantId") Long tenantId,
                                         @Param("workflowId") Long workflowId);

    /** 每工作流最近一次执行：workflow_id / execution_id / ref_time（finished_at 优先）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT DISTINCT ON (workflow_id) workflow_id, id AS execution_id,
                   COALESCE(finished_at, started_at) AS ref_time
            FROM execution
            WHERE tenant_id = #{tenantId} AND dry_run = false AND status IN ('SUCCEEDED','PARTIAL')
            ORDER BY workflow_id, id DESC
            """)
    List<Map<String, Object>> selectLatestExecutions(@Param("tenantId") Long tenantId);
}