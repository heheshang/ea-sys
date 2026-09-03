package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 智能客服：确定性决策策略器（纯函数，不依赖任何 LLM）。
 *
 * <p>输入全量会话历史 → 输出单一意图：纯文本收尾 {@link Reply}，或导语 + 工具调用列表
 * {@link Tool}（可同轮并发多个 query_stats）。工具执行结果回到历史后，策略器按
 * “结果尾部未被消费”分支组装引用式回答（知识库引用原文段落、数据问答引用真实指标）。
 *
 * <p>意图路由优先级：取消 > 工作流触发结果/查询结果收尾 > 创建运营工作流
 * （切换到工作流创建助手）> 触发工作流（先查可用列表，唯一则直接发起，多个则点名匹配）>
 * 数据指标（到达率/留存率/漏斗/效果）> 人群圈定 > 知识库问答 > 能力菜单。
 *
 * <p>LLM 接入（如 ultrathink）后：模型位替换即可，工具、HITL、卡片事件均为框架级能力。
 */
public final class AssistantPolicy {

    /** 助手工具名（与 API 模块工具注册对齐）。 */
    public static final String TOOL_SEARCH_KB = "search_kb";
    public static final String TOOL_QUERY_STATS = "query_stats";
    public static final String TOOL_SEARCH_AUDIENCES = "search_audiences";
    public static final String TOOL_SEARCH_WORKFLOWS = "search_workflows";
    public static final String TOOL_TRIGGER_WORKFLOW = "trigger_workflow";
    public static final String TOOL_BEGIN_WORKFLOW_DIALOGUE = "begin_workflow_dialogue";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String KB_MISS_NOTE = "知识库中暂未找到与「%s」相关的内容，可先上传相关文档（支持 txt/md/csv/xlsx/docx/pdf）后再问";

    private AssistantPolicy() {
    }

    /** 决策输出（sealed：纯文本回复 或 导语 + 工具调用）。 */
    public sealed interface Action permits Reply, Tool {
    }

    /** 纯文本回复（逐句分片，前端打字机逐块渲染）。 */
    public record Reply(List<String> chunks) implements Action {
    }

    /** 一次工具调用意图。 */
    public record Call(String name, Map<String, Object> input) {
    }

    /** 工具轮：导语 + 一个或多个工具调用（查询类工具可并发，如同时查到达率与留存率）。 */
    public record Tool(List<String> prefaceChunks, List<Call> calls) implements Action {
    }

    /** 指定工具最近结果快照。 */
    private record ToolSnapshot(ToolResultState state, JsonNode output) {
    }

    public static Action decide(List<Msg> history) {
        List<String> userTexts = userTexts(history);
        String last = lastWord(userTexts);

        // 1. 明确取消 → 收尾引导（不发起任何工具）
        if (isCancel(last)) {
            return new Reply(sentences("好的，已取消。需要帮助随时说，比如：创建运营工作流、查看到达率/留存率、圈定人群，或知识库问答。"));
        }

        // 2. 已执行的触发结果落在历史尾部（人工确认后）→ 触发收尾
        ToolSnapshot trigger = lastTool(history, TOOL_TRIGGER_WORKFLOW);
        if (trigger != null && tailIs(history, TOOL_TRIGGER_WORKFLOW)) {
            if (trigger.state == ToolResultState.DENIED) {
                return new Reply(sentences("好的，已取消本次触发。"));
            }
            return replyForTriggered(trigger.output);
        }

        // 2.5 各类查询工具结果落在尾部 → 组装引用式回答（结果未被消费）
        if (tailIs(history, TOOL_SEARCH_KB)) {
            return replyFromKb(history);
        }
        if (tailIs(history, TOOL_QUERY_STATS)) {
            return replyFromStats(history);
        }
        if (tailIs(history, TOOL_SEARCH_AUDIENCES)) {
            return replyFromAudiences(history);
        }
        if (tailIs(history, TOOL_BEGIN_WORKFLOW_DIALOGUE)) {
            return new Reply(sentences("已切换到工作流创建助手。接下来直接描述你的运营工作流需求（人群、触发时间、通道），我帮你生成草稿并载入画布。"));
        }
        if (tailIs(history, TOOL_SEARCH_WORKFLOWS)) {
            return continueOrList(history);
        }

        // 3. 新用户消息 → 意图路由（创建优先于触发：创建需求中常含“每天9点触发”）
        if (isCreateIntent(last)) {
            return new Tool(sentences("好的，为你切换到工作流创建助手，接下来描述需求即可。"),
                    List.of(new Call(TOOL_BEGIN_WORKFLOW_DIALOGUE, Map.of())));
        }
        if (isTriggerIntent(last)) {
            Long matched = matchWorkflowId(last, lastTool(history, TOOL_SEARCH_WORKFLOWS));
            if (matched != null) {
                return new Tool(sentences("好的，找到匹配的工作流，为你发起执行（将征求你的确认）。"),
                        List.of(new Call(TOOL_TRIGGER_WORKFLOW, Map.of("workflowId", matched))));
            }
            return new Tool(sentences("我来查一下当前已发布的工作流。"),
                    List.of(new Call(TOOL_SEARCH_WORKFLOWS, Map.of())));
        }
        List<String> topics = statsTopics(last);
        if (!topics.isEmpty()) {
            List<Call> calls = topics.stream().map(t -> new Call(TOOL_QUERY_STATS, Map.of("topic", t))).toList();
            return new Tool(sentences("好的，为你查询相关运营数据。"), calls);
        }
        if (isAudienceIntent(last)) {
            return new Tool(sentences("好的，帮你拉取当前租户的人群列表。"),
                    List.of(new Call(TOOL_SEARCH_AUDIENCES, Map.of())));
        }
        if (isQuestion(last)) {
            return new Tool(sentences("让我在知识库里检索一下。"),
                    List.of(new Call(TOOL_SEARCH_KB, Map.of("query", last))));
        }
        if (isElaborate(last)) {
            String prev = previousRequirement(last, userTexts);
            if (prev != null) {
                return new Tool(sentences("好的，结合上下文再帮你细查。"),
                        List.of(new Call(TOOL_SEARCH_KB, Map.of("query", prev))));
            }
        }
        return new Reply(menuChunks());
    }

    // ---- 意图判定 ----

    private static boolean isCancel(String last) {
        return Set.of("取消", "算了", "不要", "不用了", "不触发", "撤销", "放弃").contains(last.trim());
    }

    private static boolean isCreateIntent(String text) {
        boolean action = containsAny(text, "创建工作流", "新建工作流", "创建流程", "新建流程", "创建", "新建", "设计", "搭建",
                "做一个", "搞一个", "生成一个", "规划一个");
        boolean noun = containsAny(text, "工作流", "触达计划", "运营计划", "流程");
        return action && noun;
    }

    private static boolean isTriggerIntent(String text) {
        return containsAny(text, "触发", "执行", "跑一下", "立即", "马上", "启动");
    }

    private static boolean isAudienceIntent(String text) {
        return containsAny(text, "人群", "圈定", "圈选", "选人", "目标客户");
    }

    private static boolean isQuestion(String text) {
        return text.contains("？") || text.contains("?")
                || containsAny(text, "怎么", "如何", "什么", "啥", "为什么", "多少", "有哪些", "是什么",
                "怎么回事", "说明", "解释", "介绍一下", "帮我查", "查一下", "查查", "了解", "详情", "看看", "请问");
    }

    private static boolean isElaborate(String text) {
        return containsAny(text, "详细", "具体", "更多", "再讲", "展开", "细化", "说细", "深一点");
    }

    /** 数据指标主题：命中“到达率/发送类” → channel；“留存” → retention；“漏斗/转化” → funnel；工作流效果 → workflow。 */
    static List<String> statsTopics(String text) {
        List<String> topics = new ArrayList<>();
        if (containsAny(text, "到达率", "送达率", "渠道", "通道", "发送", "到达情况")) {
            topics.add("channel");
        }
        if (containsAny(text, "留存")) {
            topics.add("retention");
        }
        if (containsAny(text, "漏斗", "转化")) {
            topics.add("funnel");
        }
        if (containsAny(text, "工作流效果", "流程效果")) {
            topics.add("workflow");
        }
        if (topics.isEmpty() && containsAny(text, "指标", "效果", "数据", "运营情况")) {
            // 泛化请求：默认看渠道到达 + 留存
            topics.add("channel");
            topics.add("retention");
        }
        return topics;
    }

    private static boolean isControlWord(String t) {
        String s = t.trim();
        return isCancel(s) || containsAny(s, "确认", "可以", "好的", "嗯", "行", "开始", "同意", "确定");
    }

    // ---- 历史解析 ----

    /** 全部 USER 消息文本（按序）。 */
    private static List<String> userTexts(List<Msg> history) {
        List<String> out = new ArrayList<>();
        for (Msg m : history) {
            if (m.getRole() == MsgRole.USER) {
                String t = m.getTextContent();
                if (t != null && !t.isBlank()) {
                    out.add(t.trim());
                }
            }
        }
        return out;
    }

    private static String lastWord(List<String> userTexts) {
        return userTexts.isEmpty() ? "" : userTexts.get(userTexts.size() - 1);
    }

    /** 历史尾部的消息是否即指定工具的结果（结果未被消费）。 */
    private static boolean tailIs(List<Msg> history, String toolName) {
        if (history.isEmpty()) {
            return false;
        }
        Msg last = history.get(history.size() - 1);
        return last.getContentBlocks(ToolResultBlock.class).stream()
                .anyMatch(r -> toolName.equals(r.getName()));
    }

    /** 指定工具最近一次结果快照（含状态与输出 JSON）。 */
    private static ToolSnapshot lastTool(List<Msg> history, String toolName) {
        for (int i = history.size() - 1; i >= 0; i--) {
            for (ToolResultBlock r : history.get(i).getContentBlocks(ToolResultBlock.class)) {
                if (toolName.equals(r.getName())) {
                    return new ToolSnapshot(r.getState(), parse(resultText(r)));
                }
            }
        }
        return null;
    }

    private static JsonNode lastResultJson(List<Msg> history, String toolName) {
        ToolSnapshot s = lastTool(history, toolName);
        return s == null ? null : s.output;
    }

    private static String resultText(ToolResultBlock r) {
        for (var b : r.getOutput()) {
            if (b instanceof TextBlock tb) {
                return tb.getText();
            }
        }
        return null;
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (!s.startsWith("{") && !s.startsWith("[")) {
            return null;
        }
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 收尾文案组装（工具结果尾部） ----

    /** 知识库回答：引用命中段落原文（命中卡由控制器从工具结果同步产出）。 */
    private static Reply replyFromKb(List<Msg> history) {
        JsonNode n = lastResultJson(history, TOOL_SEARCH_KB);
        if (n == null) {
            return new Reply(sentences("知识库检索没有返回结果，换个问法试试。"));
        }
        String note = n.path("note").asText(null);
        if (note != null) {
            return new Reply(sentences(note));
        }
        StringBuilder sb = new StringBuilder();
        List<String> docNames = new ArrayList<>();
        int i = 0;
        for (JsonNode hit : n.path("hits")) {
            if (i >= 3) {
                break;
            }
            String name = hit.path("documentName").asText("未知文档");
            if (!docNames.contains(name)) {
                docNames.add(name);
            }
            String content = hit.path("content").asText("").trim();
            sb.append(i + 1).append(". 摘自《").append(name).append("》：").append(content).append("\n");
            i++;
        }
        sb.append("以上回答引用自：").append(String.join("、", docNames)).append("。可以继续追问细节。");
        return new Reply(sentences(sb.toString()));
    }

    /**
     * 数据回答：按主题引用真实指标（前端同时渲染统计卡片）。同轮可并发多主题
     * （到达率 + 留存率同时查），结果可能落在相邻多条消息中：从尾部向上收集
     * 整个结果批次（遇不含工具结果的消息为止），逐主题汇总，不丢主题。
     */
    private static Reply replyFromStats(List<Msg> history) {
        List<JsonNode> topics = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            List<ToolResultBlock> blocks = history.get(i).getContentBlocks(ToolResultBlock.class);
            if (blocks.isEmpty()) {
                // 越过结果批次，抵达模型调用/用户消息 → 结束收集
                break;
            }
            for (ToolResultBlock r : blocks) {
                if (!TOOL_QUERY_STATS.equals(r.getName())) {
                    continue;
                }
                JsonNode n = parse(resultText(r));
                if (n != null && n.isObject()) {
                    n.path("topics").forEach(topics::add);
                }
            }
        }
        if (topics.isEmpty()) {
            return new Reply(sentences("暂未取到相关数据，稍后再试试。"));
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : topics) {
            String topic = t.path("topic").asText("");
            switch (topic) {
                case "channel" -> {
                    sb.append("近 7 天渠道送达：");
                    List<String> parts = new ArrayList<>();
                    for (JsonNode c : t.path("items")) {
                        parts.add(c.path("channel").asText("?") + " " + pct(c.path("deliveryRate").asDouble(0))
                                + "（" + c.path("sent").asLong(0) + "/" + c.path("total").asLong(0) + "）");
                    }
                    sb.append(String.join("；", parts)).append("。");
                }
                case "retention" -> sb.append("近 ").append(t.path("days").asInt(0))
                        .append(" 天留存：上一周期 ").append(t.path("cohort").asLong(0))
                        .append(" 人，本周期留存 ").append(t.path("retained").asLong(0))
                        .append(" 人，留存率约 ").append(pct(t.path("rate").asDouble(0))).append("。");
                case "funnel" -> sb.append("转化漏斗：圈选 ").append(t.path("seeded").asLong(0))
                        .append(" 人 → 执行 ").append(t.path("executed").asLong(0))
                        .append(" 人（").append(pct(t.path("seededToExecutedRate").asDouble(0)))
                        .append("）→ 触达 ").append(t.path("reached").asLong(0))
                        .append(" 人（").append(pct(t.path("executedToReachedRate").asDouble(0))).append("）。");
                case "workflow" -> {
                    sb.append("工作流效果：");
                    List<String> parts = new ArrayList<>();
                    for (JsonNode w : t.path("items")) {
                        parts.add("《" + w.path("workflowName").asText("?") + "》触达 "
                                + w.path("reached").asLong(0) + "、留存 " + w.path("retained").asLong(0)
                                + "（" + pct(w.path("retentionRate").asDouble(0)) + "）");
                    }
                    sb.append(String.join("；", parts)).append("。");
                }
                default -> {
                }
            }
        }
        return new Reply(sentences(sb.toString()));
    }

    /** 人群回答：列举现有圈定（不新建人群）。 */
    private static Reply replyFromAudiences(List<Msg> history) {
        JsonNode n = lastResultJson(history, TOOL_SEARCH_AUDIENCES);
        if (n == null) {
            return new Reply(sentences("暂未取到人群数据，稍后再试试。"));
        }
        String note = n.path("note").asText(null);
        if (note != null) {
            return new Reply(sentences(note));
        }
        StringBuilder sb = new StringBuilder("当前租户人群（共 ").append(n.size()).append(" 个）：\n");
        int i = 0;
        for (JsonNode a : n) {
            if (i >= 6) {
                break;
            }
            sb.append(i + 1).append(". ").append(a.path("name").asText("?"));
            String rule = a.path("rule").asText("");
            if (!rule.isBlank()) {
                sb.append("（").append(rule).append("）");
            }
            sb.append('\n');
            i++;
        }
        sb.append("可以对我说「执行工作流」或「创建运营工作流」继续。");
        return new Reply(sentences(sb.toString()));
    }

    /**
     * 工作流列表收尾：0 个 → 引导创建；1 个 → 直接发起触发（HITL 待确认）；
     * 多个 → 点名匹配。
     */
    private static Action continueOrList(List<Msg> history) {
        JsonNode n = lastResultJson(history, TOOL_SEARCH_WORKFLOWS);
        if (n == null || !n.isArray()) {
            return new Reply(sentences("暂未取到工作流列表，稍后再试试。"));
        }
        if (n.isEmpty()) {
            return new Reply(sentences("当前没有已发布的工作流可执行。可在工作台创建并发布，或直接对我说「创建一个运营工作流」。"));
        }
        if (n.size() == 1) {
            JsonNode w = n.get(0);
            return new Tool(sentences("找到唯一已发布的工作流《" + w.path("name").asText("?") + "》，为你发起执行（将征求你的确认）。"),
                    List.of(new Call(TOOL_TRIGGER_WORKFLOW, Map.of("workflowId", w.path("id").asLong()))));
        }
        return new Reply(sentences(listWorkflowNames(n) + "。对我说「执行《名字》」即可开始。"));
    }

    /** 触发收尾：执行结果摘要（触发卡由控制器从工具结果同步产出）。 */
    private static Reply replyForTriggered(JsonNode output) {
        if (output == null) {
            return new Reply(sentences("已收到触发结果，详情见卡片。"));
        }
        String name = output.path("workflowName").asText("?");
        String error = output.path("error").asText("");
        if (!error.isBlank()) {
            return new Reply(sentences("触发《" + name + "》执行失败：" + error + "。请检查工作流是否已发布且包含人群节点。"));
        }
        StringBuilder sb = new StringBuilder("已触发工作流《").append(name).append("》执行：")
                .append("执行号 ").append(output.path("executionId").asText("-"))
                .append("，覆盖 ").append(output.path("totalMembers").asLong(0)).append(" 人，")
                .append("状态 ").append(output.path("status").asText("?")).append("。")
                .append("执行报告可在「工作流管理」中查看。");
        return new Reply(sentences(sb.toString()));
    }

    // ---- 能力菜单 ----

    private static List<String> menuChunks() {
        return WorkflowDialoguePolicy.sentences("你好，我是 AI 智能客服，可以帮你："
                + "1. 知识库问答：上传文档（txt/md/csv/xlsx/docx/pdf）后直接提问；"
                + "2. 创建运营工作流：描述人群、时机、通道，生成草稿并载入画布；"
                + "3. 触发工作流：选中已发布工作流直接执行；"
                + "4. 人群圈定：查看当前租户人群；"
                + "5. 数据查看：到达率、留存率、转化漏斗、工作流效果；"
                + "6. 日常闲聊。"
                + "试试对我说：「查一下近 30 天留存」或「创建一个每天 9 点的运营工作流」。");
    }

    // ---- 工具与文案小工具 ----

    /** 从最近一次 search_workflows 结果中按用户文本点名匹配工作流 id（唯一命中才返回）。 */
    private static Long matchWorkflowId(String text, ToolSnapshot search) {
        if (search == null || search.output == null || !search.output.isArray()) {
            return null;
        }
        List<Long> matched = new ArrayList<>();
        for (JsonNode w : search.output) {
            String name = w.path("name").asText("");
            if (!name.isBlank() && text.contains(name)) {
                matched.add(w.path("id").asLong());
            }
        }
        return matched.size() == 1 ? matched.get(0) : null;
    }

    private static String listWorkflowNames(JsonNode arr) {
        StringBuilder sb = new StringBuilder("当前可执行的工作流有：");
        int i = 0;
        for (JsonNode w : arr) {
            sb.append("\n").append(i + 1).append(". 《").append(w.path("name").asText("?")).append("》");
            i++;
        }
        return sb.toString();
    }

    /** 追问“详细点”时回退到上一条非控制词用户消息作为检索词。 */
    private static String previousRequirement(String last, List<String> userTexts) {
        for (int i = userTexts.size() - 2; i >= 0; i--) {
            String t = userTexts.get(i);
            if (!isControlWord(t) && !t.equals(last)) {
                return t;
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100d);
    }

    private static List<String> sentences(String text) {
        return WorkflowDialoguePolicy.sentences(text);
    }
}