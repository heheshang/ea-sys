package com.easysys.api.service;

import com.easysys.api.dto.retention.ChannelEffectView;
import com.easysys.api.dto.retention.FunnelView;
import com.easysys.api.dto.retention.IntervalRetentionView;
import com.easysys.api.dto.retention.WorkflowEffectView;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.AudienceSnapshotMemberMapper;
import com.easysys.api.mapper.EventMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.WorkflowMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 留存看板聚合（只读查询，全部显式 tenant_id 过滤，通过 MyBatis-Plus mapper）：
 * 漏斗、区间留存、渠道效果、工作流效果。活跃信号 = 行为事件（event 表）。
 */
@Service
public class RetentionService {

    private final ExecutionMapper executionMapper;
    private final DeliveryRecordMapper deliveryMapper;
    private final AudienceSnapshotMapper snapshotMapper;
    private final AudienceSnapshotMemberMapper memberMapper;
    private final EventMapper eventMapper;
    private final WorkflowMapper workflowMapper;

    public RetentionService(ExecutionMapper executionMapper, DeliveryRecordMapper deliveryMapper,
                            AudienceSnapshotMapper snapshotMapper, AudienceSnapshotMemberMapper memberMapper,
                            EventMapper eventMapper, WorkflowMapper workflowMapper) {
        this.executionMapper = executionMapper;
        this.deliveryMapper = deliveryMapper;
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
        this.eventMapper = eventMapper;
        this.workflowMapper = workflowMapper;
    }

    /**
     * 转化漏斗：圈选（就绪快照成员数）→ 执行（进入执行的快照成员去重数）→ 触达（SENT/DELIVERED 去重人数）。
     * workflowId 空 = 租户全量。
     */
    public FunnelView funnel(Long workflowId) {
        Long tenantId = TenantContext.require();
        List<Long> snapshotIds = executionMapper.selectAudienceSnapshotIds(tenantId, workflowId);
        long seeded = 0;
        long executed = 0;
        if (!snapshotIds.isEmpty()) {
            seeded = snapshotMapper.sumMemberCount(tenantId, snapshotIds);
            executed = memberMapper.countDistinctContacts(snapshotIds);
        }
        long reached = deliveryMapper.countDistinctReached(tenantId, workflowId);
        return new FunnelView(workflowId, seeded, executed, reached,
                seeded == 0 ? 0 : (double) executed / seeded,
                executed == 0 ? 0 : (double) reached / executed);
    }

    /**
     * 区间留存（N 天窗口）：cohort = [now-2N, now-N) 活跃人数；留存 = cohort 中 [now-N, now) 仍活跃人数。
     */
    public IntervalRetentionView intervalRetention(int days) {
        Long tenantId = TenantContext.require();
        Instant now = Instant.now();
        Instant priorStart = now.minusSeconds(days * 2L * 86400);
        Instant priorEnd = now.minusSeconds(days * 86400L);
        Instant currentEnd = now;

        long cohort = eventMapper.countDistinctActive(tenantId, priorStart, priorEnd);
        long retained = eventMapper.countDistinctRetained(tenantId, priorStart, priorEnd, priorEnd, currentEnd);
        return new IntervalRetentionView(days, cohort, retained, cohort == 0 ? 0 : (double) retained / cohort,
                priorStart, priorEnd, priorEnd, currentEnd);
    }

    /**
     * 渠道效果：since 以来每渠道 总数 / 送达（SENT+DELIVERED）/ 失败 / 去重触达人数 / 送达率。
     * eventName 非空时附加 conversion = 送达人群中发生过该事件的人数。
     */
    public ChannelEffectView channelEffect(Instant since, String eventName) {
        Long tenantId = TenantContext.require();
        boolean withEvent = eventName != null && !eventName.isBlank();
        List<ChannelEffectView.ChannelEffectItem> items = new ArrayList<>();
        for (Map<String, Object> row : deliveryMapper.selectChannelStats(tenantId, since, withEvent ? eventName : null)) {
            String channel = (String) row.get("channel");
            long total = ((Number) row.get("total")).longValue();
            long sent = ((Number) row.get("sent")).longValue();
            long failed = ((Number) row.get("failed")).longValue();
            long contacts = ((Number) row.get("contacts")).longValue();
            items.add(new ChannelEffectView.ChannelEffectItem(channel, total, sent, failed, contacts,
                    total == 0 ? 0 : (double) sent / total));
        }
        return new ChannelEffectView(items);
    }

    /**
     * 工作流效果：每个工作流最近一次执行的触达人数 + 留存（近 N 天窗口内仍有活跃事件的触达人数，
     * 窗口以当前时刻锚定，与区间留存口径一致）。
     */
    public WorkflowEffectView workflowEffect(int days) {
        Long tenantId = TenantContext.require();
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Instant now = Instant.now();
        List<WorkflowEffectView.WorkflowEffectItem> items = new ArrayList<>();
        List<Long> workflowIds = new ArrayList<>();
        List<Map<String, Object>> executions = executionMapper.selectLatestExecutions(tenantId);
        for (Map<String, Object> ex : executions) {
            workflowIds.add(((Number) ex.get("workflow_id")).longValue());
        }
        Map<Long, String> namesById = new HashMap<>();
        for (Workflow wf : workflowMapper.selectBatchIds(workflowIds)) {
            namesById.put(wf.getId(), wf.getName());
        }
        for (Map<String, Object> ex : executions) {
            long execId = ((Number) ex.get("execution_id")).longValue();
            long workflowId = ((Number) ex.get("workflow_id")).longValue();
            long reached = deliveryMapper.countDistinctByExecution(tenantId, execId);
            long retained = deliveryMapper.countRetainedByExecution(tenantId, execId, since, now);
            items.add(new WorkflowEffectView.WorkflowEffectItem(workflowId, namesById.get(workflowId),
                    reached, retained, reached == 0 ? 0 : (double) retained / reached));
        }
        return new WorkflowEffectView(items);
    }
}