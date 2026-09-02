package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.workflow.DryRunRequest;
import com.easysys.api.dto.workflow.DryRunResponse;
import com.easysys.api.dto.workflow.SaveWorkflowRequest;
import com.easysys.api.dto.workflow.ValidationResponse;
import com.easysys.api.dto.workflow.WorkflowEdgeSpec;
import com.easysys.api.dto.workflow.WorkflowNodeSpec;
import com.easysys.api.dto.workflow.WorkflowView;
import com.easysys.api.entity.AudienceSnapshot;
import com.easysys.api.entity.AudienceSnapshotMember;
import com.easysys.api.entity.Contact;
import com.easysys.api.entity.ContactAttribute;
import com.easysys.api.entity.ContactTag;
import com.easysys.api.mapper.AudienceSnapshotMapper;
import com.easysys.api.mapper.AudienceSnapshotMemberMapper;
import com.easysys.api.mapper.ContactAttributeMapper;
import com.easysys.api.mapper.ContactMapper;
import com.easysys.api.mapper.ContactTagMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.engine.dag.DagValidator;
import com.easysys.engine.entity.Execution;
import com.easysys.engine.entity.Workflow;
import com.easysys.engine.entity.WorkflowEdge;
import com.easysys.engine.entity.WorkflowNode;
import com.easysys.engine.mapper.ExecutionMapper;
import com.easysys.engine.mapper.WorkflowEdgeMapper;
import com.easysys.engine.mapper.WorkflowNodeMapper;
import com.easysys.engine.mapper.WorkflowMapper;
import com.easysys.engine.rule.ConditionCompiler;
import com.easysys.engine.service.AbstractDagExecutor;
import com.easysys.engine.service.DryRunExecutor;
import com.easysys.engine.service.WorkflowExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流（画布）：版本化保存 → 结构校验 → 发布 → 干跑 → 报告。
 * 校验/干跑委托 engine（DagValidator / ConditionCompiler / DryRunExecutor）；
 * 画布行与快照成员画像在此装配。
 * 版本化：DRAFT 覆盖当前版本行；PUBLISHED 后保存生成 version+1 新行，旧发布行保留。
 */
@Service
public class WorkflowService {

    private static final int MEMBER_BATCH = 500;

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final ExecutionMapper executionMapper;
    private final DagValidator dagValidator;
    private final ConditionCompiler conditionCompiler;
    private final DryRunExecutor dryRunExecutor;
    private final WorkflowExecutor workflowExecutor;
    private final AudienceSnapshotMapper snapshotMapper;
    private final AudienceSnapshotMemberMapper memberMapper;
    private final ContactMapper contactMapper;
    private final ContactAttributeMapper attributeMapper;
    private final ContactTagMapper tagMapper;
    private final ObjectMapper json;

    public WorkflowService(WorkflowMapper workflowMapper, WorkflowNodeMapper nodeMapper,
                           WorkflowEdgeMapper edgeMapper, ExecutionMapper executionMapper,
                           DagValidator dagValidator, ConditionCompiler conditionCompiler,
                           DryRunExecutor dryRunExecutor, WorkflowExecutor workflowExecutor,
                           AudienceSnapshotMapper snapshotMapper,
                           AudienceSnapshotMemberMapper memberMapper, ContactMapper contactMapper,
                           ContactAttributeMapper attributeMapper, ContactTagMapper tagMapper,
                           ObjectMapper json) {
        this.workflowMapper = workflowMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.executionMapper = executionMapper;
        this.dagValidator = dagValidator;
        this.conditionCompiler = conditionCompiler;
        this.dryRunExecutor = dryRunExecutor;
        this.workflowExecutor = workflowExecutor;
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
        this.contactMapper = contactMapper;
        this.attributeMapper = attributeMapper;
        this.tagMapper = tagMapper;
        this.json = json;
    }

    /** 保存画布。id 空 → 新建 v1 DRAFT；已发布/归档 → 新版本行；DRAFT → 覆盖当前行。 */
    @Transactional
    public WorkflowView save(Long id, SaveWorkflowRequest req, String operator) {
        Long tenantId = TenantContext.require();
        ValidationResponse vr = ValidationResponse.of(validateCanvas(req.nodes(), req.edges()));
        if (!vr.valid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "画布校验不通过: " + String.join("; ", vr.errors()));
        }
        Workflow wf;
        if (id == null) {
            wf = new Workflow();
            wf.setTenantId(tenantId);
            wf.setName(req.name().trim());
            wf.setDescription(req.description());
            wf.setStatus("draft");
            wf.setVersion(1);
            wf.setCreatedBy(operator);
            wf.setCreatedAt(Instant.now());
            wf.setUpdatedAt(Instant.now());
            workflowMapper.insert(wf);
            wf.setRefId(wf.getId()); // 首行：业务 id = 主键
            workflowMapper.updateById(wf);
        } else {
            wf = editableRow(id);
            if (wf == null) {
                requireWorkflow(id); // id 不存在 → 404
                throw new BizException(ErrorCode.BAD_REQUEST, "工作流无可用版本（仅剩已归档行）: " + id);
            }
            if (!"draft".equals(wf.getStatus())) {
                // PUBLISHED/ARCHIVED：保留旧行，新建 version+1
                Workflow next = new Workflow();
                next.setTenantId(tenantId);
                next.setName(req.name().trim());
                next.setDescription(req.description());
                next.setStatus("draft");
                next.setRefId(wf.getRefId() == null ? id : wf.getRefId()); // 继承业务 id，版本行同族
                next.setVersion((wf.getVersion() == null ? 0 : wf.getVersion()) + 1);
                next.setCreatedBy(operator);
                next.setCreatedAt(Instant.now());
                next.setUpdatedAt(Instant.now());
                workflowMapper.insert(next);
                wf = next;
            } else {
                wf.setName(req.name().trim());
                wf.setDescription(req.description());
                wf.setUpdatedAt(Instant.now());
                workflowMapper.updateById(wf);
            }
        }
        replaceCanvas(wf.getRefId(), wf.getVersion(), req);
        return view(wf);
    }

    /** 校验当前版本行的画布结构（非 body）：发布/干跑前由 DB 数据兜底校验。 */
    public ValidationResponse validate(Long id) {
        Workflow wf = editableRow(id);
        if (wf == null) {
            requireWorkflow(id); // 404
            return ValidationResponse.of(List.of("工作流无可用版本"));
        }
        return ValidationResponse.of(validateRows(wf));
    }

    /** 发布：DRAFT → published；旧发布行 archived。发布前重校验当前行。 */
    @Transactional
    public WorkflowView publish(Long id) {
        Workflow wf = draftRow(id);
        if (wf == null) {
            if (editableRow(id) == null) {
                requireWorkflow(id); // 404
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "没有待发布的草稿版本");
        }
        List<String> errors = validateRows(wf);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "画布校验不通过: " + String.join("; ", errors));
        }
        // 归档同 id 下其它 published 历史版本行
        List<Workflow> sameId = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>()
                        .eq(Workflow::getRefId, wf.getRefId())
                        .eq(Workflow::getStatus, "published"));
        for (Workflow old : sameId) {
            old.setStatus("archived");
            old.setUpdatedAt(Instant.now());
            workflowMapper.updateById(old);
        }
        wf.setStatus("published");
        wf.setPublishedAt(Instant.now());
        wf.setUpdatedAt(Instant.now());
        workflowMapper.updateById(wf);
        return view(wf);
    }

    /** 干跑：对已发布版本 + 冻结快照成员模拟执行；失败场景在报告内可见（execution=FAILED）。 */
    public DryRunResponse dryRun(Long id, DryRunRequest req) {
        List<AbstractDagExecutor.MemberContext> members = executionPreamble(id, req, "干跑");
        Workflow wf = publishedRow(id);
        WorkflowSnapshot ws = canvasOf(wf);
        AbstractDagExecutor.ExecutionReport report = dryRunExecutor.execute(wf, ws.nodes, ws.edges,
                req.audienceSnapshotId(), members);
        return DryRunResponse.from(report);
    }

    /** 真实触达执行：与干跑同语义，ACTION 节点真实下发（治理/频率/幂等拦截计入 skipped）。 */
    public DryRunResponse execute(Long id, DryRunRequest req) {
        List<AbstractDagExecutor.MemberContext> members = executionPreamble(id, req, "执行");
        Workflow wf = publishedRow(id);
        WorkflowSnapshot ws = canvasOf(wf);
        AbstractDagExecutor.ExecutionReport report = workflowExecutor.execute(wf, ws.nodes, ws.edges,
                req.audienceSnapshotId(), members);
        return DryRunResponse.from(report);
    }

    /** 干跑/真实执行的公共前置校验：已发布版本 + 快照就绪，装配成员画像。 */
    private List<AbstractDagExecutor.MemberContext> executionPreamble(Long id, DryRunRequest req, String action) {
        if (publishedRow(id) == null) {
            requireWorkflow(id); // 404（id 不存在）
            throw new BizException(ErrorCode.BAD_REQUEST, "请先发布再" + action);
        }
        if (req == null || req.audienceSnapshotId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, action + "需要 audienceSnapshotId");
        }
        AudienceSnapshot snap = snapshotMapper.selectById(req.audienceSnapshotId());
        if (snap == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "快照不存在: " + req.audienceSnapshotId());
        }
        if (!"ready".equals(snap.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "快照未就绪（当前: " + snap.getStatus() + "）");
        }
        return loadMembers(snap);
    }

    private record WorkflowSnapshot(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
    }

    private WorkflowSnapshot canvasOf(Workflow wf) {
        List<WorkflowNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, wf.getRefId())
                        .eq(WorkflowNode::getVersion, wf.getVersion()));
        List<WorkflowEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, wf.getRefId())
                        .eq(WorkflowEdge::getVersion, wf.getVersion()));
        return new WorkflowSnapshot(nodes, edges);
    }

    /** 按 executionId 查询执行/干跑报告。 */
    public DryRunResponse report(Long executionId) {
        Execution e = executionMapper.selectById(executionId);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "执行不存在: " + executionId);
        }
        return DryRunResponse.from(dryRunExecutor.report(executionId));
    }

    public WorkflowView get(Long id) {
        Workflow wf = editableRow(id);
        if (wf == null) {
            requireWorkflow(id); // 404
            throw new BizException(ErrorCode.BAD_REQUEST, "工作流无可用版本: " + id);
        }
        return view(wf);
    }

    private WorkflowView view(Workflow wf) {
        List<WorkflowNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, wf.getRefId())
                        .eq(WorkflowNode::getVersion, wf.getVersion()));
        List<WorkflowEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, wf.getRefId())
                        .eq(WorkflowEdge::getVersion, wf.getVersion()));
        List<WorkflowNodeSpec> nodeSpecs = nodes.stream()
                .map(n -> new WorkflowNodeSpec(n.getNodeKey(), n.getType(), n.getName(),
                        parse(n.getConfig()), parse(n.getPosition())))
                .toList();
        List<WorkflowEdgeSpec> edgeSpecs = edges.stream()
                .map(e -> new WorkflowEdgeSpec(e.getSourceKey(), e.getTargetKey(), parse(e.getCondition())))
                .toList();
        return new WorkflowView(wf.getRefId(), wf.getName(), wf.getDescription(), wf.getStatus(), wf.getVersion(),
                wf.getPublishedAt(), wf.getCreatedBy(), wf.getCreatedAt(), nodeSpecs, edgeSpecs);
    }

    /** 保存时校验 body 画布：结构（DagValidator）+ 每条出边条件 DSL 编译。 */
    private List<String> validateCanvas(List<WorkflowNodeSpec> nodes, List<WorkflowEdgeSpec> edges) {
        List<DagValidator.NodeDef> ndefs = nodes.stream()
                .map(n -> new DagValidator.NodeDef(n.key(), n.type(), n.config()))
                .toList();
        List<DagValidator.EdgeDef> edefs = edges.stream()
                .map(e -> new DagValidator.EdgeDef(e.source(), e.target(), e.condition()))
                .toList();
        List<String> errors = new ArrayList<>(dagValidator.validate(ndefs, edefs).errors());
        for (DagValidator.EdgeDef e : edefs) {
            if (e.condition() != null && !e.condition().isNull() && !e.condition().isEmpty()) {
                try {
                    conditionCompiler.compile(e.condition().toString());
                } catch (Exception ex) {
                    errors.add("边 " + e.sourceKey() + "→" + e.targetKey() + " 条件 DSL 非法: " + ex.getMessage());
                }
            }
        }
        return errors;
    }

    /** 校验 DB 当前版本行（发布前兜底，防绕过保存直改状态）。 */
    private List<String> validateRows(Workflow wf) {
        List<WorkflowNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, wf.getRefId())
                        .eq(WorkflowNode::getVersion, wf.getVersion()));
        List<WorkflowEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, wf.getRefId())
                        .eq(WorkflowEdge::getVersion, wf.getVersion()));
        if (nodes.isEmpty()) {
            return List.of("画布为空，无法校验");
        }
        return validateCanvas(nodes.stream()
                        .map(n -> new WorkflowNodeSpec(n.getNodeKey(), n.getType(), n.getName(),
                                parse(n.getConfig()), parse(n.getPosition())))
                        .toList(),
                edges.stream()
                        .map(e -> new WorkflowEdgeSpec(e.getSourceKey(), e.getTargetKey(), parse(e.getCondition())))
                        .toList());
    }

    /** 替换某版本的画布行（先删旧行再插新行；同版本内节点/边幂等重建）。 */
    private void replaceCanvas(Long workflowId, Integer version, SaveWorkflowRequest req) {
        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, workflowId)
                .eq(WorkflowNode::getVersion, version));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWorkflowId, workflowId)
                .eq(WorkflowEdge::getVersion, version));

        Long tenantId = TenantContext.require();
        for (WorkflowNodeSpec n : req.nodes()) {
            WorkflowNode en = new WorkflowNode();
            en.setTenantId(tenantId);
            en.setWorkflowId(workflowId);
            en.setVersion(version);
            en.setNodeKey(n.key());
            en.setType(n.type());
            en.setName(n.name() == null || n.name().isBlank() ? n.type() : n.name().trim());
            en.setConfig(n.config() == null ? "{}" : n.config().toString());
            en.setPosition(n.position() == null ? "{}" : n.position().toString());
            nodeMapper.insert(en);
        }
        for (WorkflowEdgeSpec e : req.edges()) {
            WorkflowEdge en = new WorkflowEdge();
            en.setTenantId(tenantId);
            en.setWorkflowId(workflowId);
            en.setVersion(version);
            en.setSourceKey(e.source());
            en.setTargetKey(e.target());
            en.setCondition(e.condition() == null ? null : e.condition().toString());
            edgeMapper.insert(en);
        }
    }

    /** 快照成员 → 画像上下文（contact.* = 直属列 + attributes + tags + 退订渠道展开）。 */
    private List<AbstractDagExecutor.MemberContext> loadMembers(AudienceSnapshot snap) {
        List<Long> ids = memberMapper.selectList(
                        new LambdaQueryWrapper<AudienceSnapshotMember>()
                                .eq(AudienceSnapshotMember::getSnapshotId, snap.getId()))
                .stream().map(AudienceSnapshotMember::getContactId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Contact> contacts = new HashMap<>();
        for (int i = 0; i < ids.size(); i += MEMBER_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + MEMBER_BATCH, ids.size()));
            for (Contact c : contactMapper.selectBatchIds(batch)) {
                contacts.put(c.getId(), c);
            }
        }
        // attributes: contactId → {key: value}（value 为 jsonb 标量）
        Map<Long, Map<String, Object>> attrs = new HashMap<>();
        for (int i = 0; i < ids.size(); i += MEMBER_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + MEMBER_BATCH, ids.size()));
            for (ContactAttribute a : attributeMapper.selectList(
                    new LambdaQueryWrapper<ContactAttribute>().in(ContactAttribute::getContactId, batch))) {
                attrs.computeIfAbsent(a.getContactId(), k -> new HashMap<>())
                        .put(a.getKey(), scalar(a.getValue()));
            }
        }
        Map<Long, List<String>> tags = new HashMap<>();
        for (int i = 0; i < ids.size(); i += MEMBER_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + MEMBER_BATCH, ids.size()));
            for (ContactTag t : tagMapper.selectList(
                    new LambdaQueryWrapper<ContactTag>().in(ContactTag::getContactId, batch))) {
                tags.computeIfAbsent(t.getContactId(), k -> new ArrayList<>()).add(t.getTag());
            }
        }

        List<AbstractDagExecutor.MemberContext> members = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Contact c = contacts.get(id);
            if (c == null) {
                // 快照冻结后成员被软删：跳过（DB 查询已过滤 deleted）
                continue;
            }
            Map<String, Object> ctx = new LinkedHashMap<>();
            Map<String, Object> a = attrs.get(id);
            if (a != null) {
                ctx.putAll(a);
            }
            putIfNotNull(ctx, "status", c.getStatus());
            putIfNotNull(ctx, "phone", c.getPhone());
            putIfNotNull(ctx, "email", c.getEmail());
            putIfNotNull(ctx, "externalId", c.getExternalId());
            ctx.put("suppressedChannels", suppressedChannels(c.getSuppression()));
            List<String> t = tags.get(id);
            ctx.put("tags", t == null ? List.of() : t);
            members.add(new AbstractDagExecutor.MemberContext(id, ctx, Map.of(), Map.of()));
        }
        return members;
    }

    /**
     * 退订渠道解析：suppression jsonb → List[channel]（"*" = 全渠道退订）。
     * 格式 {"channels":["sms","email"]} 或 {"all":true}；空/非法 → 空列表（不拦截）。
     */
    private List<String> suppressedChannels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode n = json.readTree(raw);
            if (n.isArray()) {
                List<String> out = new ArrayList<>();
                n.forEach(x -> out.add(x.asText()));
                return out;
            }
            if (n.isObject()) {
                JsonNode all = n.get("all");
                if (all != null && all.isBoolean() && all.asBoolean()) {
                    return List.of("*");
                }
                JsonNode channels = n.get("channels");
                if (channels != null && channels.isArray()) {
                    List<String> out = new ArrayList<>();
                    channels.forEach(x -> out.add(x.asText()));
                    return out;
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 不拦截（真实数据由写入侧校验）
        }
        return List.of();
    }

    /** jsonb 属性值 → QLExpress 可比标量（数字/布尔/字符串），非法 JSON 原样字符串。 */
    private Object scalar(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode n = json.readTree(raw);
            // 保类型：数值/布尔属性保留为可比较标量（contact.level > 2 依赖数字语义）
            if (n.isNumber()) {
                return n.numberValue();
            }
            if (n.isBoolean()) {
                return n.asBoolean();
            }
            if (n.isTextual()) {
                return n.asText();
            }
            return raw;
        } catch (JsonProcessingException e) {
            return raw;
        }
    }

    private static void putIfNotNull(Map<String, Object> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }

    private Workflow requireWorkflow(Long id) {
        Workflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, id)
                .orderByDesc(Workflow::getVersion)
                .last("LIMIT 1"));
        if (wf == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + id);
        }
        return wf;
    }

    /** 业务 id 下当前可用行：优先 DRAFT（编辑态），其次最新 published 快照。 */
    private Workflow editableRow(Long id) {
        Workflow draft = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, id)
                .eq(Workflow::getStatus, "draft")
                .last("LIMIT 1"));
        if (draft != null) {
            return draft;
        }
        return workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, id)
                .eq(Workflow::getStatus, "published")
                .orderByDesc(Workflow::getVersion)
                .last("LIMIT 1"));
    }

    /** 业务 id 下的草稿行（publish 入口）。 */
    private Workflow draftRow(Long id) {
        return workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, id)
                .eq(Workflow::getStatus, "draft")
                .last("LIMIT 1"));
    }

    /** 业务 id 下最新已发布行（干跑入口）。 */
    private Workflow publishedRow(Long id) {
        return workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getRefId, id)
                .eq(Workflow::getStatus, "published")
                .orderByDesc(Workflow::getVersion)
                .last("LIMIT 1"));
    }
}