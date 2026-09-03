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
import java.util.Set;

/**
 * 对话式创建工作流：确定性决策策略器。
 *
 * <p>纯函数：输入全量会话历史（{@code List<Msg>}），输出意图（{@link Action}）。
 * 决定何时发起查询工具轮、何时追问缺失要素、何时触发 {@code plan_workflow} 生成草稿
 * （生成前置 HITL：草稿产出前由框架 RequireUserConfirmEvent 闸门人工确认）。
 * 不依赖任何 LLM，主模型未接入时同路径可跑；LLM 接入（如 ultrathink）后
 * HITL 仍为框架级能力，与模型无关。
 *
 * <p>需求画像复用 {@link WorkflowPlanner#profileOf}：触发显式表达与否、人群短语、
 * 通道、延迟。
 */
public final class WorkflowDialoguePolicy {

    /** 对话工具名（与 API 模块工具注册对齐）。 */
    public static final String TOOL_LIST_CHANNELS = "list_channels";
    public static final String TOOL_SEARCH_TEMPLATES = "search_templates";
    public static final String TOOL_SEARCH_AUDIENCES = "search_audiences";
    public static final String TOOL_PLAN_WORKFLOW = "plan_workflow";

    /** 确认控制词：精确整串匹配（区别于真实需求文本，如「生成工作流」不命中「生成」）。 */
    static final Set<String> CONFIRM_WORDS = Set.of(
            "确认", "确认生成", "可以", "好的", "嗯", "行", "开始", "同意", "确定", "就这么办", "没问题");

    /** 取消控制词：用户放弃本轮生成。 */
    static final Set<String> CANCEL_WORDS = Set.of("取消", "算了", "不要", "不用了", "不生成", "撤销", "放弃");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowDialoguePolicy() {
    }

    /** 决策输出（sealed：仅三种意图）。 */
    public sealed interface Action permits Reply, Query, Draft {
    }

    /** 纯文本回复（逐句分片，前端打字机逐块渲染）。 */
    public record Reply(List<String> chunks) implements Action {
    }

    /** 查询轮：导语 + 批量发起三个只读查询工具（通道/模板/人群）。 */
    public record Query(List<String> prefaceChunks) implements Action {
    }

    /** 生成轮：确认前导 + plan_workflow 工具调用（框架 HITL 闸门拦截等待人工确认）。 */
    public record Draft(String prompt, List<String> prefaceChunks) implements Action {
    }

    /** plan_workflow 最近结果快照。 */
    private record PlanSnapshot(ToolResultState state, JsonNode output) {
    }

    /**
     * 决策主入口。
     *
     * @param history 全量会话历史（USER/ASSISTANT/TOOL/… 消息按序）
     */
    public static Action decide(List<Msg> history) {
        List<String> userTexts = userTexts(history);
        String last = lastWord(userTexts);
        PlanSnapshot plan = lastPlan(history);
        boolean querySeen = hasToolResult(history, TOOL_SEARCH_AUDIENCES);

        // 1. 明确取消 → 收尾引导（不发起任何工具）
        if (isCancel(last)) {
            return new Reply(sentences("好的，已取消。可以继续补充需求，或调整触发时间、人群、发送通道。"));
        }

        // 2. 草稿已生成（SUCCESS 结果落在历史尾部）→ 收尾文案（草稿卡已就绪）。
        //    不能用 isConfirm(last)：HITL 的确认消息走 METADATA_CONFIRM_RESULTS 传递，
        //    不落入 context，模型轮历史尾部是需求文本而非「确认生成」。
        if (plan != null && plan.state == ToolResultState.SUCCESS && lastResultIsPlan(history)) {
            return replyForDraftReady(plan.output);
        }

        // 2.5 草稿被人工取消（DENIED 结果在历史尾部）→ 收尾引导，不再发起任何工具
        if (plan != null && plan.state == ToolResultState.DENIED && lastResultIsPlan(history)) {
            return new Reply(sentences("好的，已取消。可以继续补充需求，或调整触发时间、人群、发送通道。"));
        }

        // 3. 查询轮未完成 → 导语 + 三查询工具（喂真实数据供后续画像判定）
        if (!querySeen) {
            return new Query(sentences("我来帮你创建工作流。先查询当前租户的发送通道、模板和人群数据，稍后根据你的需求生成草稿。"));
        }

        // 4. 画像：需求文本 = 全部非控制词 USER 消息拼接
        String requirement = String.join("\n", requirementTexts(userTexts));
        WorkflowPlanner.WorkflowProfile p = WorkflowPlanner.profileOf(requirement);
        boolean audienceOk = p.audiencePhrase() != null && !p.audiencePhrase().isBlank();
        boolean triggerOk = p.triggerExplicit();

        // 5. 生成轮：要素齐备 → 确认前导 + plan_workflow（框架 ASK → HITL 卡片）
        if (audienceOk && triggerOk) {
            return new Draft(requirement, draftPreface(p));
        }
        // 6. 缺项 → 追问（引用真实数据）
        return new Reply(clarify(p, history, audienceOk, triggerOk));
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

    /** 画像用文本：剔除确认/取消等控制词消息。 */
    private static List<String> requirementTexts(List<String> userTexts) {
        List<String> out = new ArrayList<>();
        for (String t : userTexts) {
            if (!isControlWord(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean isControlWord(String t) {
        String s = t.trim();
        return CONFIRM_WORDS.contains(s) || CANCEL_WORDS.contains(s);
    }

    private static String lastWord(List<String> userTexts) {
        return userTexts.isEmpty() ? "" : userTexts.get(userTexts.size() - 1);
    }

    private static boolean isCancel(String last) {
        return CANCEL_WORDS.contains(last.trim());
    }

    private static boolean isConfirm(String last) {
        return CONFIRM_WORDS.contains(last.trim());
    }

    private static boolean hasToolResult(List<Msg> history, String toolName) {
        for (Msg m : history) {
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                if (toolName.equals(r.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 最近一条 plan_workflow 的 ToolResultBlock（含状态与输出 JSON）。 */
    private static PlanSnapshot lastPlan(List<Msg> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            for (ToolResultBlock r : history.get(i).getContentBlocks(ToolResultBlock.class)) {
                if (TOOL_PLAN_WORKFLOW.equals(r.getName())) {
                    return new PlanSnapshot(r.getState(), parse(resultText(r)));
                }
            }
        }
        return null;
    }

    /** 历史尾部的消息是否就是 plan_workflow 工具结果（执行刚完成、结语尚未消费）。 */
    private static boolean lastResultIsPlan(List<Msg> history) {
        if (history.isEmpty()) {
            return false;
        }
        Msg last = history.get(history.size() - 1);
        return last.getContentBlocks(ToolResultBlock.class).stream()
                .anyMatch(r -> TOOL_PLAN_WORKFLOW.equals(r.getName()));
    }

    /** 指定工具最近一次结果的 JSON（若输出可解析）。 */
    private static JsonNode lastResultJson(List<Msg> history, String toolName) {
        for (int i = history.size() - 1; i >= 0; i--) {
            for (ToolResultBlock r : history.get(i).getContentBlocks(ToolResultBlock.class)) {
                if (toolName.equals(r.getName())) {
                    JsonNode n = parse(resultText(r));
                    if (n != null) {
                        return n;
                    }
                }
            }
        }
        return null;
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

    // ---- 文案组装 ----

    /** 草稿就绪收尾（节点/边数来自生成结果）。 */
    private static Reply replyForDraftReady(JsonNode output) {
        String nodes = "-";
        String edges = "-";
        if (output != null) {
            nodes = String.valueOf(output.path("workflowDraft").path("nodes").size());
            edges = String.valueOf(output.path("workflowDraft").path("edges").size());
        }
        return new Reply(sentences("草稿已生成（%s 个节点 %s 条边）。可以在下方卡片点击「载入画布」查看调整，或继续告诉我修改点（如换模板、改时间）。"
                .formatted(nodes, edges)));
    }

    /** 生成确认前导：摘要 + 确认指引（确认卡片承接）。 */
    private static List<String> draftPreface(WorkflowPlanner.WorkflowProfile p) {
        StringBuilder sb = new StringBuilder("已了解需求：");
        sb.append(p.triggerLabel());
        if (p.audiencePhrase() != null) {
            sb.append("，面向「").append(p.audiencePhrase()).append("」");
        }
        sb.append("，通过").append(channelsText(p.channels()));
        if (p.delayMinutes() > 0) {
            sb.append("，延迟 ").append(p.delayMinutes()).append(" 分钟执行");
        }
        int nodes = 1 + (p.delayMinutes() > 0 ? 1 : 0) + Math.max(1, p.channels().size()) + 1;
        sb.append("。将生成约 ").append(nodes).append(" 个节点的工作流草稿，请确认后生成。");
        return sentences(sb.toString());
    }

    /** 缺项追问：指明缺哪项，引用现有人群/通道真实数据。 */
    private static List<String> clarify(WorkflowPlanner.WorkflowProfile p, List<Msg> history,
                                        boolean audienceOk, boolean triggerOk) {
        List<String> missing = new ArrayList<>();
        if (!audienceOk) {
            missing.add("人群");
        }
        if (!triggerOk) {
            missing.add("触发时间");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已了解：").append(p.triggerLabel());
        if (p.channels().isEmpty()) {
            sb.append("，通道未识别（默认短信）");
        } else {
            sb.append("，通过").append(channelsText(p.channels()));
        }
        sb.append("。还需要补充：").append(String.join("、", missing)).append("。");

        if (!audienceOk) {
            List<String> names = audienceNames(history);
            sb.append(" 人群：");
            if (names.isEmpty()) {
                sb.append("当前租户还没有人群，描述目标人群即可（如「近30天未购买会员」），保存前我再提示圈选；");
            } else {
                sb.append("现有可选人群：").append(String.join("、", names))
                        .append("。可直接用现有人群名，或描述新人群特征（如「近30天未购买会员」）；");
            }
        }
        if (!triggerOk) {
            sb.append(" 触发时间：需要告知触发时机，如「每天上午9点」「每周一 10:00」「每3天18:00」「每月1号」。");
        }
        return sentences(sb.toString());
    }

    // ---- 数据素材（追问引用） ----

    private static List<String> audienceNames(List<Msg> history) {
        JsonNode n = lastResultJson(history, TOOL_SEARCH_AUDIENCES);
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            int k = 0;
            for (JsonNode a : n) {
                if (k++ >= 5) {
                    break;
                }
                String name = a.path("name").asText("");
                if (!name.isBlank()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static String channelsText(List<String> channels) {
        List<String> cn = new ArrayList<>();
        for (String c : channels) {
            cn.add(channelCn(c));
        }
        if (cn.isEmpty()) {
            cn.add("短信");
        }
        return String.join("、", cn);
    }

    static String channelCn(String channel) {
        String ch = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        switch (ch) {
            case "sms":
                return "短信";
            case "email":
                return "邮件";
            case "wecom":
                return "企微";
            case "app_push":
            case "push":
                return "App 推送";
            default:
                return ch;
        }
    }

    // ---- 分句（打字机粒度） ----

    /** 中文分句：按句末标点切分；超长句按逗号二次切分，单块 ≤ 60 字。知识库分块亦复用（跨模块）。 */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            buf.appendCodePoint(cp);
            boolean boundary = "。！？!?；;\n".indexOf(cp) >= 0;
            if (boundary && buf.length() >= 1) {
                trimChunk(out, buf);
                buf.setLength(0);
            }
            i += Character.charCount(cp);
        }
        if (!buf.isEmpty()) {
            trimChunk(out, buf);
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    private static void trimChunk(List<String> out, StringBuilder buf) {
        String s = buf.toString().trim();
        if (s.isBlank()) {
            return;
        }
        if (s.length() <= 60) {
            out.add(s);
            return;
        }
        // 超长句按逗号/顿号二次切分
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            part.appendCodePoint(cp);
            boolean soft = "，,、：:".indexOf(cp) >= 0;
            if (soft && part.length() >= 12) {
                out.add(part.toString());
                part.setLength(0);
            } else if (part.length() >= 60) {
                out.add(part.toString());
                part.setLength(0);
            }
            i += Character.charCount(cp);
        }
        if (!part.isEmpty()) {
            out.add(part.toString());
        }
    }
}