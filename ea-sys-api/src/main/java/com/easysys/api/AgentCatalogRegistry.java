package com.easysys.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 驾驶舱图谱内置目录：与代码同源的八类知识领域登记项（只读常量，不落表）。
 * {@link com.easysys.api.service.CockpitService#listGraph} 按 module 取内置 ∪ 用户登记行，
 * 同 (module, entry_key) 用户行覆盖内置项（状态/描述/版本以用户为准）。
 *
 * <p>来源锚定现有实现：TOOL 9 个工具位均可在代码中找到类（Assistant 助手/工作流对话工具类）；
 * ONTOLOGY 七个业务实体（workflow/audience/contact/template/event/channel/strategy）；
 * SKILL 为系统已具备的确定性能力；MCP/SUBAGENT 为待接入占位（默认 DISABLED）；
 * MEMORY 为会话/工作区观测项；EVALUATION 为评测中心自身。</p>
 */
public final class AgentCatalogRegistry {

    public static final String ONTOLOGY = "ONTOLOGY";
    public static final String SKILL = "SKILL";
    public static final String TOOL = "TOOL";
    public static final String MCP = "MCP";
    public static final String SUBAGENT = "SUBAGENT";
    public static final String MEMORY = "MEMORY";
    public static final String KNOWLEDGE = "KNOWLEDGE";
    public static final String EVALUATION = "EVALUATION";

    /** 八类知识领域（模块 tab 顺序）。 */
    public static final List<String> MODULES = List.of(ONTOLOGY, SKILL, TOOL, MCP, SUBAGENT, MEMORY, KNOWLEDGE, EVALUATION);

    /** 内置登记项（module → items，保持声明顺序）。 */
    private static final Map<String, List<Item>> BUILTIN = new LinkedHashMap<>();

    static {
        put(ONTOLOGY, "workflow", "工作流", "运营触达编排 DAG（节点/边/干跑/发布）", "ENABLED", "1.0");
        put(ONTOLOGY, "audience", "人群", "圈选规则与快照（含流失人群快照）", "ENABLED", "1.0");
        put(ONTOLOGY, "contact", "联系人", "用户实体与标签/属性", "ENABLED", "1.0");
        put(ONTOLOGY, "template", "模板", "多渠道发送模板（channel/name/content/status）", "ENABLED", "1.0");
        put(ONTOLOGY, "event", "事件", "用户行为事件流（Redis Streams 事件队列）", "ENABLED", "1.0");
        put(ONTOLOGY, "channel", "通道", "触达通道配置与可用性（channel_config）", "ENABLED", "1.0");
        put(ONTOLOGY, "strategy", "策略", "人群分层策略与通道路由编排（layer_strategy）", "ENABLED", "1.0");

        put(SKILL, "layer_planning", "人群分层策略制定", "按通道可用性分层触达策略生成（LAYER）", "ENABLED", "1.0");
        put(SKILL, "workflow_dialogue", "工作流对话生成", "自然语言需求 → 工作流 DAG 草稿（对话 + plan_workflow）", "ENABLED", "1.0");
        put(SKILL, "kb_qa", "知识库问答", "确定性 RAG：词频预筛 + BM25 打分 + 引用式回答", "ENABLED", "1.0");
        put(SKILL, "churn_warning", "流失风险预警", "成员活跃度扫描 + 流失分级回写（CHURN）", "ENABLED", "1.0");

        put(TOOL, "search_kb", "search_kb", "企业知识库检索（RAG 引用回答）", "ENABLED", "1.0");
        put(TOOL, "query_stats", "query_stats", "运营数据问答（channel/retention/funnel/workflow）", "ENABLED", "1.0");
        put(TOOL, "search_audiences", "search_audiences", "人群圈定查询（助手/工作流对话共用）", "ENABLED", "1.0");
        put(TOOL, "search_workflows", "search_workflows", "已发布工作流列表（触发候选）", "ENABLED", "1.0");
        put(TOOL, "trigger_workflow", "trigger_workflow", "触发工作流执行（HITL 人工确认闸门）", "ENABLED", "1.0");
        put(TOOL, "begin_workflow_dialogue", "begin_workflow_dialogue", "切换到工作流创建助手会话", "ENABLED", "1.0");
        put(TOOL, "list_channels", "list_channels", "查询租户可用触达通道", "ENABLED", "1.0");
        put(TOOL, "search_templates", "search_templates", "查询可用发送模板（含正文）", "ENABLED", "1.0");
        put(TOOL, "plan_workflow", "plan_workflow", "生成工作流 DAG 草稿（HITL ask 闸门）", "ENABLED", "1.0");

        put(MCP, "mcp_gateway", "MCP 网关", "MCP 协议接入位（待接入外部服务发现/调用）", "DISABLED", "0.1");
        put(SUBAGENT, "subagent_orchestrator", "子 Agent 编排", "子任务拆解/并行执行位（待接入）", "DISABLED", "0.1");
        put(MEMORY, "agent_session_store", "Agent 会话/工作区观测", "Redis agentscope 会话状态与工作区数据键（easysys:agentscope:）", "ENABLED", "1.0");
        put(KNOWLEDGE, "knowledge_base", "知识库", "文档解析 + 分块索引（kb_document/kb_document_chunk）", "ENABLED", "1.0");
        put(EVALUATION, "evaluation_center", "评测中心", "数据集 + 11 个内置评测器 + 批量运行报告", "ENABLED", "1.0");
    }

    private AgentCatalogRegistry() {
    }

    /** 内置项视图。 */
    public record Item(String module, String entryKey, String name, String description,
                       String status, String version, String source) {
    }

    /** 指定 module 的内置目录（未识别 module 返回空列表）。 */
    public static List<Item> builtin(String module) {
        List<Item> items = BUILTIN.get(module);
        return items == null ? List.of() : items;
    }

    /** 全部内置目录（按 MODULES 顺序拍平）。 */
    public static List<Item> all() {
        List<Item> out = new ArrayList<>();
        for (String module : MODULES) {
            out.addAll(builtin(module));
        }
        return out;
    }

    private static void put(String module, String key, String name, String description, String status, String version) {
        BUILTIN.computeIfAbsent(module, k -> new ArrayList<>())
                .add(new Item(module, key, name, description, status, version, "builtin"));
    }
}