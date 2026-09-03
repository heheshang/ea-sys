package com.easysys.engine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.channel.ChannelAdapter;
import com.easysys.channel.ChannelAdapter.SendRequest;
import com.easysys.common.tenant.TenantContext;
import com.easysys.engine.EngineException;
import com.easysys.engine.entity.DeliveryRecord;
import com.easysys.engine.entity.Execution;
import com.easysys.engine.entity.Template;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.ExecutionNodeStateMapper;
import com.easysys.engine.mapper.TemplateMapper;
import com.easysys.engine.model.ExecutionStatus;
import com.easysys.engine.model.TriggerType;
import com.easysys.engine.rule.ConditionCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

/**
 * 真实触达执行器：复用 {@link AbstractDagExecutor} 的 DAG 推进，
 * ACTION 节点逐成员执行 画像治理（status/suppression 优先）→ 幂等查重（delivery_record 唯一键）
 * → 频率控制（Redisson）→ 模板渲染（FreeMarker）→ 通道下发（ChannelRouter）→ 回执状态回流。
 *
 * 语义：
 * - 治理/频率未通过、重复触达均不产生下发记录，计入节点 output.skipped（原因分类统计）
 * - 单成员渲染/下发失败 → 记 FAILED 下发记录且不中断整批；执行收尾时存在 FAILED 记录 → PARTIAL
 * - 结构性错误（模板缺失/频道未注册/config 缺失）→ 节点 FAILED、执行 FAILED（报告可见病根）
 */
@Service
public class WorkflowExecutor extends AbstractDagExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final DeliveryRecordMapper deliveryRecordMapper;
    private final TemplateMapper templateMapper;
    private final TemplateRenderer templateRenderer;
    private final ChannelRouter channelRouter;
    private final FrequencyGuard frequencyGuard;
    private final DeliveryNotifier deliveryNotifier;

    public WorkflowExecutor(ExecutionMapper executionMapper, ExecutionNodeStateMapper stateMapper,
                            ConditionCompiler compiler, DeliveryRecordMapper deliveryRecordMapper,
                            TemplateMapper templateMapper, TemplateRenderer templateRenderer,
                            ChannelRouter channelRouter, @Lazy FrequencyGuard frequencyGuard,
                            ObjectProvider<AgentSplitHandler> agentSplitHandler,
                            DeliveryNotifier deliveryNotifier) {
        super(executionMapper, stateMapper, compiler, agentSplitHandler);
        this.deliveryRecordMapper = deliveryRecordMapper;
        this.templateMapper = templateMapper;
        this.templateRenderer = templateRenderer;
        this.channelRouter = channelRouter;
        this.frequencyGuard = frequencyGuard;
        this.deliveryNotifier = deliveryNotifier;
    }

    /** 手动真实执行入口：dryRun=false，委托统一 8 参入口（含 PARTIAL 降级）。 */
    @Transactional
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members) {
        return execute(workflow, nodes, edges, audienceSnapshotId, members, false,
                TriggerType.MANUAL.name(), null);
    }

    /**
     * 全部触发形态（MANUAL/SCHEDULED/IMMEDIATE/EVENT/API）统一真实执行入口：
     * dryRun=false 且存在 FAILED 下发记录 → 执行状态降级 PARTIAL（不再伪 SUCCEEDED）。
     */
    @Override
    @Transactional
    public ExecutionReport execute(Workflow workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                   Long audienceSnapshotId, List<MemberContext> members, boolean dryRun,
                                   String triggerType, String triggerPayload) {
        ExecutionReport report = super.execute(workflow, nodes, edges, audienceSnapshotId, members, dryRun,
                triggerType, triggerPayload);
        // super.execute 的 @Transactional 被自调用绕过，由本方法外层事务覆盖整段执行
        if (!dryRun && ExecutionStatus.SUCCEEDED.name().equals(report.status())
                && hasFailedDeliveries(report.executionId())) {
            Execution update = new Execution();
            update.setId(report.executionId());
            update.setStatus(ExecutionStatus.PARTIAL.name());
            update.setUpdatedAt(Instant.now());
            executionMapper.updateById(update);
            log.warn("真实执行存在失败下发，状态降级 PARTIAL executionId={} workflowId={} members={}",
                    report.executionId(), report.workflowId(), report.totalMembers());
            return report(report.executionId());
        }
        return report;
    }

    @Override
    protected void handleAction(Long executionId, WorkflowNode node, LinkedHashSet<Long> here,
                                Map<Long, MemberContext> byId, ObjectNode output) {
        JsonNode cfg = parseConfig(node);
        String channel = cfg.path("channel").asText(null);
        Long templateId = cfg.path("templateId").isNumber() ? cfg.path("templateId").asLong() : null;
        if (channel == null || templateId == null) {
            throw new EngineException("ACTION 节点 " + node.getNodeKey() + " 缺少 channel/templateId 配置");
        }
        int contacts = here == null ? 0 : here.size();
        output.put("channel", channel);
        output.put("templateId", templateId);
        output.put("contacts", contacts);
        // 零成员分支不落执行失败：模板缺失/通道未注册只在真正下发时才报错
        if (here == null || here.isEmpty()) {
            finishOutput(output, cfg, 0, 0, 0, Map.of());
            return;
        }
        Template template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new EngineException("模板不存在: " + templateId);
        }
        ChannelAdapter adapter = channelRouter.require(channel);
        Long tenantId = TenantContext.require();

        // 本执行本节点已下发的成员（幂等前置查询；DB 唯一键兜底并发重复）
        Set<Long> delivered = new HashSet<>();
        for (DeliveryRecord r : deliveryRecordMapper.selectList(
                new LambdaQueryWrapper<DeliveryRecord>()
                        .eq(DeliveryRecord::getExecutionId, executionId)
                        .eq(DeliveryRecord::getNodeKey, node.getNodeKey())
                        .in(DeliveryRecord::getContactId, here))) {
            delivered.add(r.getContactId());
        }

        int sent = 0;
        int alreadySent = 0;
        int failed = 0;
        Map<String, Long> skipped = new LinkedHashMap<>();
        for (Long contactId : here) {
            MemberContext member = byId.get(contactId);
            Map<String, Object> contact = member == null ? Map.of() : member.contact();
            String skip = skipReason(contact, channel);
            if (skip != null) {
                skipped.merge(skip, 1L, Long::sum);
                continue;
            }
            if (delivered.contains(contactId)) {
                alreadySent++;
                continue;
            }
            FrequencyGuard.Decision decision = frequencyGuard.checkAndConsume(tenantId, contactId, channel);
            if (decision != FrequencyGuard.Decision.ALLOW) {
                skipped.merge(decision.label(), 1L, Long::sum);
                continue;
            }

            String content;
            try {
                content = templateRenderer.render(template.getContent(), contact);
            } catch (Exception e) {
                record(executionId, tenantId, contactId, node, channel, templateId, null, null, "FAILED",
                        renderError(e));
                failed++;
                continue;
            }

            SendResultHolder send;
            try {
                send = doSend(adapter, tenantId, contactId, executionId, node, templateId, content, contact);
            } catch (Exception e) {
                record(executionId, tenantId, contactId, node, channel, templateId, content, null, "FAILED",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed++;
                continue;
            }
            if (send.result().success()) {
                // 受理成功 ≠ 触达成功：落 SENT 待回执，真正触达交由回调服务（notify）异步执行，
                // 通道回执经 notify 回调本服务后更新为 DELIVERED/FAILED。
                record(executionId, tenantId, contactId, node, channel, templateId, content,
                        send.channelMessageId(), "SENT", send.result().error());
                boolean notified = deliveryNotifier.deliver(tenantId, executionId, contactId,
                        node.getNodeKey(), channel, templateId, content, send.channelMessageId(),
                        channelAddress(adapter.channel(), contact));
                if (notified) {
                    sent++;
                } else {
                    updateStatus(send.channelMessageId(), "FAILED", "回调服务不可达，无法确认真正触达");
                    failed++;
                }
            } else {
                record(executionId, tenantId, contactId, node, channel, templateId, content,
                        send.channelMessageId(), "FAILED",
                        send.result().error() == null ? "通道下发失败" : send.result().error());
                failed++;
            }
        }
        finishOutput(output, cfg, sent, alreadySent, failed, skipped);
    }

    private void finishOutput(ObjectNode output, JsonNode cfg, int sent, int alreadySent, int failed,
                              Map<String, Long> skipped) {
        output.put("sent", sent);
        output.put("alreadySent", alreadySent);
        output.put("failed", failed);
        output.set("skipped", objectMapper.valueToTree(skipped));
        JsonNode unitCost = cfg.get("unitCost");
        if (unitCost != null && unitCost.isNumber()) {
            output.put("estimatedCost", unitCost.decimalValue().multiply(BigDecimal.valueOf(sent)));
        }
        if (failed > 0) {
            output.put("error", failed + " 条下发失败");
        }
    }

    /** 真实通道受理：send 成功即可获得 channelMsgId，真正的投递结果由通道异步回执（经 notify）回流。 */
    private SendResultHolder doSend(ChannelAdapter adapter, Long tenantId, Long contactId, Long executionId,
                                    WorkflowNode node, Long templateId, String content,
                                    Map<String, Object> contact) {
        return new SendResultHolder(adapter.send(new SendRequest(tenantId, contactId, executionId,
                node.getNodeKey(), String.valueOf(templateId), content,
                tenantId + ":" + contactId + ":" + executionId + ":" + node.getNodeKey(),
                channelAddress(adapter.channel(), contact))));
    }

    /** 通道收件地址：sms → phone、email → email、wechat → wechatOpenid；画像缺该键（含非目标通道）→ null，真实通道由适配器拒发。 */
    private static String channelAddress(String channel, Map<String, Object> contact) {
        String key = switch (channel) {
            case "sms" -> "phone";
            case "email" -> "email";
            case "wechat" -> "wechatOpenid";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        Object v = contact.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 治理拦截：contact.status 非 active 或该渠道被退订 → 跳过（优先级高于一切，见架构文档 §5）。 */
    private String skipReason(Map<String, Object> contact, String channel) {
        Object status = contact.get("status");
        if (status != null && !"active".equals(String.valueOf(status))) {
            return "status";
        }
        Object suppressed = contact.get("suppressedChannels");
        if (suppressed instanceof List<?> channels
                && (channels.contains("*") || channels.contains(channel))) {
            return "suppressed";
        }
        return null;
    }

    /** 回调路径状态更新：按 channelMsgId 覆盖（同执行内该条唯一）。 */
    private void updateStatus(String channelMsgId, String status, String error) {
        DeliveryRecord r = new DeliveryRecord();
        r.setStatus(status);
        r.setError(error);
        r.setUpdatedAt(Instant.now());
        deliveryRecordMapper.update(r, new LambdaQueryWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getChannelMsgId, channelMsgId));
    }

    /** 幂等落库：唯一键冲突（并发/重放）视为已下发，忽略。 */
    private void record(Long executionId, Long tenantId, Long contactId, WorkflowNode node, String channel,
                        Long templateId, String content, String channelMsgId, String status, String error) {
        DeliveryRecord r = new DeliveryRecord();
        r.setTenantId(tenantId);
        r.setContactId(contactId);
        r.setExecutionId(executionId);
        r.setNodeKey(node.getNodeKey());
        r.setChannel(channel);
        r.setTemplateId(templateId);
        r.setContent(content);
        r.setChannelMsgId(channelMsgId);
        r.setStatus(status);
        r.setError(error);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        try {
            deliveryRecordMapper.insert(r);
        } catch (DuplicateKeyException ignored) {
            // 唯一键 (tenant_id, contact_id, execution_id, node_key) 兜底并发重复
        }
    }

    private boolean hasFailedDeliveries(Long executionId) {
        Long n = deliveryRecordMapper.selectCount(new LambdaQueryWrapper<DeliveryRecord>()
                .eq(DeliveryRecord::getExecutionId, executionId)
                .eq(DeliveryRecord::getStatus, "FAILED"));
        return n != null && n > 0;
    }

    private static String renderError(Exception e) {
        return "模板渲染失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private record SendResultHolder(ChannelAdapter.SendResult result) {
        String channelMessageId() {
            return result.channelMessageId();
        }
    }
}