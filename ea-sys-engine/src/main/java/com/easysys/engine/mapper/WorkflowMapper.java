package com.easysys.engine.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.engine.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {

    /** 跨租户扫描全部已发布工作流（定时触发调度用）：绕过租户插件，按行租户分别处理。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM workflow WHERE status = 'published' AND deleted = FALSE")
    List<Workflow> selectAllPublished();

    /** 测试辅助：清空触发链路相关业务表并重置自增，保证用例间隔离。绕过租户插件（多表 TRUNCATE 不被 jsqlparser 支持）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("TRUNCATE delivery_record, template, execution_node_state, execution, workflow_edge, " +
            "workflow_node, workflow, contact_tag, contact_attribute, contact, " +
            "audience_snapshot_member, audience_snapshot, audience, audit_log, event " +
            "RESTART IDENTITY CASCADE")
    void testTruncateAll();

    /** 测试辅助：把已发布工作流的 published_at 回拨 minutes 分钟，令定时 cron 处于到期状态。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE workflow SET published_at = now() - #{minutes} * interval '1 minute' " +
            "WHERE ref_id = #{refId} AND status = 'published'")
    void testBackdatePublishedAt(@Param("refId") Long refId, @Param("minutes") int minutes);

    /** 测试辅助：把已发布工作流的 published_at 设为指定时刻（配合固定时刻 cron 用例，避免依赖墙钟）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE workflow SET published_at = #{at}::timestamptz " +
            "WHERE ref_id = #{refId} AND status = 'published'")
    void testSetPublishedAt(@Param("refId") Long refId, @Param("at") String at);
}
