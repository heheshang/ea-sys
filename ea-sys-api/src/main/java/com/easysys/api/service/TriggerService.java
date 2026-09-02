package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.audience.SnapshotResponse;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.mapper.WorkflowNodeMapper;
import com.easysys.engine.model.TriggerConfig;
import com.easysys.engine.model.TriggerType;
import com.easysys.engine.rule.ConditionCompiler;
import com.easysys.engine.service.AbstractDagExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.quartz.CronExpression;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 触发双模式编排：
 * <ul>
 *   <li><b>定时</b>：跨租户轮询已发布 SCHEDULED 流程，按 cron（Quartz 表达式，含 ?）评估到点，
 *       逐流程 Redisson RLock 防并发双跑，圈选 audienceId 快照批量执行。</li>
 *   <li><b>事件</b>：事件导入时按 eventName 匹配 EVENT 流程，eventFilter DSL 求值命中后单用户执行。</li>
 *   <li><b>API</b>：外部系统携带单用户载荷入流（triggerType=API）。</li>
 *   <li><b>立即</b>：发布成功后即刻圈选 audienceId 快照批量执行一次（triggerType=IMMEDIATE）。</li>
 * </ul>
 * 轮询线程无 HTTP 租户上下文，故按行租户分别 set/clear {@link TenantContext}；
 * 事件/API 路径沿用调用方（HTTP 请求）的租户上下文。
 */
@Service
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);

    private static final String LOCK_PREFIX = "easysys:trigger:scheduled:lock:";
    private static final String LAST_PREFIX = "easysys:trigger:scheduled:last:";

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowService workflowService;
    private final AudienceService audienceService;
    private final ConditionCompiler conditionCompiler;
    private final RedissonClient redisson;
    private final ObjectMapper json;

    public TriggerService(WorkflowMapper workflowMapper, WorkflowNodeMapper nodeMapper,
                          WorkflowService workflowService, AudienceService audienceService,
                          ConditionCompiler conditionCompiler, RedissonClient redisson,
                          ObjectMapper json) {
        this.workflowMapper = workflowMapper;
        this.nodeMapper = nodeMapper;
        this.workflowService = workflowService;
        this.audienceService = audienceService;
        this.conditionCompiler = conditionCompiler;
        this.redisson = redisson;
        this.json = json;
    }

    /**
     * 定时调度轮询：跨租户扫描全部已发布流程。每个 SCHEDULED 流程：
     * tryLock 防多实例/多线程双跑 → 设租户 → cron 到点 → 圈选快照 → 批量执行。
     */
    @Scheduled(fixedDelayString = "${easysys.trigger.scheduled.poll-ms:20000}")
    public void scanScheduledDues() {
        List<Workflow> published = workflowMapper.selectAllPublished();
        for (Workflow wf : published) {
            RLock lock = redisson.getLock(LOCK_PREFIX + wf.getTenantId() + ":" + wf.getRefId());
            if (!lock.tryLock()) {
                continue; // 另一实例/线程正在执行该流程
            }
            try {
                TenantContext.set(new TenantInfo(wf.getTenantId()));
                try {
                    TriggerConfig tc = triggerConfigOf(wf);
                    if (tc.isScheduled()) {
                        Instant due = nextDue(wf, tc);
                        if (due != null) {
                            SnapshotResponse snap = audienceService.circle(tc.audienceId());
                            workflowService.executeScheduled(wf, tc, snap.id(), due);
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
            } catch (Exception e) {
                log.warn("定时触发失败 tenantId={} workflowId={}", wf.getTenantId(), wf.getRefId(), e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 事件触发：匹配当前租户已发布 EVENT 流程，eventFilter 命中即以该用户单成员执行。
     * 调用方（EventService）已设租户上下文。
     */
    public void fireEvent(Long contactId, String eventName, Map<String, Object> payload) {
        Long tenantId = TenantContext.require();
        List<Workflow> published = workflowMapper.selectAllPublished();
        AbstractDagExecutor.MemberContext base = null;
        Map<String, Object> event = payload == null ? Map.of() : payload;
        for (Workflow wf : published) {
            if (wf.getTenantId() == null || !wf.getTenantId().equals(tenantId)) {
                continue;
            }
            TriggerConfig tc;
            try {
                tc = triggerConfigOf(wf);
            } catch (Exception ex) {
                continue;
            }
            if (!tc.isEvent() || !eventName.equals(tc.eventName())) {
                continue;
            }
            if (tc.eventFilter() != null && !tc.eventFilter().isEmpty()) {
                if (base == null) {
                    base = workflowService.memberContextOf(contactId);
                }
                boolean match;
                try {
                    ConditionCompiler.CompiledCondition cc =
                            conditionCompiler.compile(tc.eventFilter().toString());
                    match = conditionCompiler.evaluate(cc, event, base.contact(), base.history());
                } catch (Exception ex) {
                    log.warn("事件过滤求值失败 event={} workflowId={}", eventName, wf.getRefId(), ex);
                    continue;
                }
                if (!match) {
                    continue;
                }
            }
            try {
                workflowService.executeSingle(wf, contactId, event, TriggerType.EVENT.name());
            } catch (Exception ex) {
                log.warn("事件触发执行失败 event={} workflowId={}", eventName, wf.getRefId(), ex);
            }
        }
    }

    /**
     * 立即触发：已发布流程 TRIGGER 配置为 IMMEDIATE 时，发布成功后即刻圈选 audienceId
     * 快照批量执行一次（triggerType=IMMEDIATE）。调用方（发布请求线程）已设租户上下文；
     * 失败仅告警不阻断发布（与定时调度同策略）。
     */
    public void fireImmediate(Long refId) {
        Long tenantId = TenantContext.require();
        Workflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, refId)
                .eq(Workflow::getTenantId, tenantId)
                .eq(Workflow::getStatus, "published")
                .last("LIMIT 1"));
        if (wf == null) {
            return;
        }
        TriggerConfig tc;
        try {
            tc = triggerConfigOf(wf);
        } catch (Exception e) {
            log.warn("立即触发配置读取失败 tenantId={} workflowId={}", tenantId, refId, e);
            return;
        }
        if (!tc.isImmediate() || tc.audienceId() == null) {
            return; // 非立即触发 / 缺人群 → 静默跳过，发布不阻断
        }
        try {
            SnapshotResponse snap = audienceService.circle(tc.audienceId());
            workflowService.executeImmediate(wf, tc, snap.id());
        } catch (Exception e) {
            log.warn("立即触发执行失败 tenantId={} workflowId={}", tenantId, refId, e);
        }
    }

    /** API 触发：外部系统按 workflowId 携带单用户载荷入流（triggerType=API）。 */
    public void fireApi(Long refId, Long contactId, Map<String, Object> payload) {
        Long tenantId = TenantContext.require();
        Workflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, refId)
                .eq(Workflow::getTenantId, tenantId)
                .eq(Workflow::getStatus, "published")
                .last("LIMIT 1"));
        if (wf == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "已发布工作流不存在: " + refId);
        }
        workflowService.executeSingle(wf, contactId, payload, TriggerType.API.name());
    }

    /** 读取某已发布流程（refId+version）TRIGGER 节点的触发配置；无 TRIGGER → 默认 MANUAL。 */
    private TriggerConfig triggerConfigOf(Workflow wf) {
        WorkflowNode trigger = nodeMapper.selectOne(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, wf.getRefId())
                .eq(WorkflowNode::getVersion, wf.getVersion())
                .eq(WorkflowNode::getType, "TRIGGER")
                .last("LIMIT 1"));
        if (trigger == null || trigger.getConfig() == null || trigger.getConfig().isBlank()) {
            return TriggerConfig.of(null);
        }
        try {
            return TriggerConfig.of(json.readTree(trigger.getConfig()));
        } catch (Exception e) {
            log.warn("TRIGGER 配置解析失败 workflowId={}: {}", wf.getRefId(), trigger.getConfig());
            return TriggerConfig.of(null);
        }
    }

    /**
     * cron 到点判定：基于上次已触发槽位（Redis）计算下一到点时刻；
     * 到点（<= now）则记录该槽位并返回。跨实例由外层 RLock 串行化，槽位防重复触发。
     */
    private Instant nextDue(Workflow wf, TriggerConfig tc) {
        if (tc.cron() == null || tc.cron().isBlank()) {
            return null;
        }
        CronExpression ce;
        try {
            ce = new CronExpression(tc.cron());
        } catch (Exception e) {
            log.warn("cron 非法 workflowId={} cron={}", wf.getRefId(), tc.cron());
            return null;
        }
        if (tc.timezone() != null && !tc.timezone().isBlank()) {
            ce.setTimeZone(TimeZone.getTimeZone(tc.timezone()));
        }
        String lastKey = LAST_PREFIX + wf.getTenantId() + ":" + wf.getRefId() + ":" + wf.getVersion();
        RBucket<String> last = redisson.getBucket(lastKey);
        Date base;
        String stored = last.get();
        if (stored != null) {
            try {
                base = Date.from(Instant.parse(stored));
            } catch (Exception e) {
                base = wf.getPublishedAt() != null ? Date.from(wf.getPublishedAt()) : Date.from(wf.getCreatedAt());
            }
        } else {
            base = wf.getPublishedAt() != null ? Date.from(wf.getPublishedAt()) : Date.from(wf.getCreatedAt());
        }
        Date next = ce.getNextValidTimeAfter(base);
        Instant now = Instant.now();
        if (next == null || !next.toInstant().isBefore(now)) {
            return null; // 无下一到点或尚未到点
        }
        last.set(next.toInstant().toString());
        return next.toInstant();
    }
}