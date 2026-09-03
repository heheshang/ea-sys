package com.easysys.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话式创建工作流确定性策略器（纯函数，无 LLM）：
 * 空历史 → 查询工具轮；缺触发 → 引用现有人群追问；要素齐备 → plan_workflow
 * 生成轮（HITL 确认前导）；草稿成功后确认 → 收尾文案；取消 → 立即终止。
 */
class WorkflowDialoguePolicyTest {

    private static final String DEMAND = "每天上午9点向近30天未购买会员发送短信";

    // ---- 消息构造 ----

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg toolResult(String name, String json) {
        return Msg.builder().role(MsgRole.TOOL)
                .content(new ToolResultBlock("tr-" + name, name,
                        TextBlock.builder().text(json).build()))
                .build();
    }

    /** plan_workflow 成功结果：状态 SUCCESS（默认构造 state 为空，须显式指定）。 */
    private static Msg planSuccessResult() {
        return Msg.builder().role(MsgRole.TOOL)
                .content(new ToolResultBlock("tr-plan_workflow", "plan_workflow",
                        List.of(TextBlock.builder().text(
                                "{\"workflowDraft\":{\"nodes\":[1,2,3,4],\"edges\":[1,2,3]},\"planSummary\":\"ok\"}")
                                .build()),
                        java.util.Map.of(), ToolResultState.SUCCESS))
                .build();
    }

    private static Msg audiencesResult() {
        return toolResult("search_audiences",
                "[{\"id\":1,\"name\":\"e2e-近30天未购买\"},{\"id\":2,\"name\":\"e2e-高价值\"}]");
    }

    private static String joined(List<String> chunks) {
        return String.join("", chunks);
    }

    // ---- 查询轮 ----

    @Test
    void emptyHistoryStartsQueryRound() {
        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(List.of());
        assertInstanceOf(WorkflowDialoguePolicy.Query.class, a);
        WorkflowDialoguePolicy.Query q = (WorkflowDialoguePolicy.Query) a;
        assertTrue(!q.prefaceChunks().isEmpty());
        assertTrue(joined(q.prefaceChunks()).contains("先查询"));
    }

    @Test
    void cancelWordTerminatesEvenBeforeQuery() {
        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(List.of(user("取消")));
        assertInstanceOf(WorkflowDialoguePolicy.Reply.class, a);
        WorkflowDialoguePolicy.Reply r = (WorkflowDialoguePolicy.Reply) a;
        assertTrue(joined(r.chunks()).contains("已取消"));
    }

    // ---- 追问 ----

    @Test
    void missingTriggerClarifiesWithExistingAudienceNames() {
        List<Msg> history = List.of(audiencesResult(), user("向近30天未购买会员发送短信"));

        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(history);
        assertInstanceOf(WorkflowDialoguePolicy.Reply.class, a);
        WorkflowDialoguePolicy.Reply r = (WorkflowDialoguePolicy.Reply) a;
        String text = joined(r.chunks());
        // 人群已表述（短语识别非空）→ 只追问触发时间，并给示例
        assertTrue(text.contains("触发时间"), text);
        assertTrue(text.contains("每天上午9点"), text);
        // 人群已表述 → 追问不再重复引用人群名单
        assertTrue(!text.contains("现有可选人群"), text);
    }

    @Test
    void missingAudienceClarifiesKeepsKnownTrigger() {
        List<Msg> history = List.of(audiencesResult(), user("每天上午9点发送短信"));

        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(history);
        assertInstanceOf(WorkflowDialoguePolicy.Reply.class, a);
        String text = joined(((WorkflowDialoguePolicy.Reply) a).chunks());
        // 缺人群 → 追问补充人群；触发已显式 → 追问中不再要求触发时间
        assertTrue(text.contains("人群"), text);
        assertTrue(text.contains("每天 09:00"), text);
        assertTrue(!text.contains("触发时间"), text);
        // 人群缺失 → 追问引用现有人群真实数据（前 5 名）
        assertTrue(text.contains("e2e-近30天未购买"), text);
        assertTrue(text.contains("e2e-高价值"), text);
    }

    // ---- 生成轮（HITL 前导） ----

    @Test
    void completeRequirementDraftsAndAsksConfirmation() {
        List<Msg> history = List.of(audiencesResult(), user(DEMAND));

        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(history);
        assertInstanceOf(WorkflowDialoguePolicy.Draft.class, a);
        WorkflowDialoguePolicy.Draft d = (WorkflowDialoguePolicy.Draft) a;
        assertEquals(DEMAND, d.prompt());
        String preface = joined(d.prefaceChunks());
        assertTrue(preface.contains("请确认后生成"), preface);
        assertTrue(preface.contains("短信"), preface);
        // 默认通道短信：trigger + action + end = 3 节点
        assertTrue(preface.contains("3 个节点"), preface);
    }

    @Test
    void draftWithDelayCountsDelayNode() {
        List<Msg> history = List.of(audiencesResult(), user("每天上午9点向近30天未购买会员发送短信,延迟2小时执行"));

        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(history);
        assertInstanceOf(WorkflowDialoguePolicy.Draft.class, a);
        String preface = joined(((WorkflowDialoguePolicy.Draft) a).prefaceChunks());
        assertTrue(preface.contains("延迟 120 分钟"), preface);
        // trigger + delay + action + end = 4 节点
        assertTrue(preface.contains("4 个节点"), preface);
    }

    // ---- 收尾 ----

    /**
     * 草稿成功后收尾：HITL 确认经 METADATA_CONFIRM_RESULTS 传输，不落对话历史；
     * 确认恢复后历史尾部即 plan_workflow 的 SUCCESS 结果 → 回复草稿摘要（不再重复生成）。
     */
    @Test
    void confirmAfterSuccessfulDraftRepliesWithSummary() {
        List<Msg> history = List.of(
                audiencesResult(),
                user(DEMAND),
                planSuccessResult());

        WorkflowDialoguePolicy.Action a = WorkflowDialoguePolicy.decide(history);
        assertInstanceOf(WorkflowDialoguePolicy.Reply.class, a);
        String text = joined(((WorkflowDialoguePolicy.Reply) a).chunks());
        assertTrue(text.contains("草稿已生成（4 个节点 3 条边）"), text);
    }

    // ---- 分句粒度 ----

    @Test
    void sentencesSplitRespectsHardAndSoftBoundaries() {
        List<String> chunks = WorkflowDialoguePolicy.sentences("先查询当前租户的通道模板与人群。再来根据你的需求生成草稿。");
        assertEquals(2, chunks.size());

        String longText = "已了解：每天 09:00，面向「近30天未购买会员」，通过 App 推送、企微、邮件、短信四个通道发送运营通知"
                + "，并延迟 30 分钟执行。将生成约 6 个节点的工作流草稿，请确认后生成。";
        for (String c : WorkflowDialoguePolicy.sentences(longText)) {
            assertTrue(c.length() <= 60, "单块超 60 字: " + c);
        }
    }
}