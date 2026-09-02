package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流规划器（WORKFLOW agent)：意图解析（触发/通道/延迟/模板/人群）与
 * AgentExecutor 闸门（schema 校验 + 置信度）全路径生效；输出为草稿语义，
 * 未匹配项留空并在计划摘要中提示人工确认。
 */
class WorkflowPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WorkflowPlanner PLANNER = new WorkflowPlanner();
    private static final AgentRunConfig CFG = AgentRunConfig.defaults();

    /** 输入快照：2 模板（短信 618 大促 / 邮件欢迎）+ 2 人群（近30天未购买 / 新注册）。 */
    private static ObjectNode context() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "");
        ArrayNode templates = input.putArray("templates");
        ObjectNode t1 = templates.addObject();
        t1.put("id", 11L);
        t1.put("channel", "sms");
        t1.put("name", "618大促通知");
        t1.put("content", "尊敬的会员，618 大促限时开启，全场满 300 减 60，快来选购吧！");
        t1.put("status", "enabled");
        ObjectNode t2 = templates.addObject();
        t2.put("id", 12L);
        t2.put("channel", "email");
        t2.put("name", "新用户欢迎邮件");
        t2.put("content", "欢迎加入，注册即领新人礼包。");
        t2.put("status", "enabled");
        ArrayNode audiences = input.putArray("audiences");
        ObjectNode a1 = audiences.addObject();
        a1.put("id", 21L);
        a1.put("name", "近30天未购买会员");
        a1.put("rule", "{\"op\":\"and\",\"rules\":[{\"field\":\"last_buy_days\",\"op\":\">\",\"value\":30}]}");
        ObjectNode a2 = audiences.addObject();
        a2.put("id", 22L);
        a2.put("name", "新注册用户");
        a2.put("rule", "{\"op\":\"and\",\"rules\":[{\"field\":\"register_days\",\"op\":\"<=\",\"value\":7}]}");
        return input;
    }

    @Test
    void dailySmsMatchesTemplateAndAudience() {
        ObjectNode input = context();
        input.put("prompt", "每天上午9点向近30天未购买会员发送短信,使用 618大促通知 模板");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        assertEquals("SUCCESS", outcome.status());
        assertNotNull(outcome.output());
        assertEquals(AgentType.WORKFLOW, outcome.audit().agentType());

        JsonNode out = outcome.output();
        assertTrue(out.path("planSummary").asText().startsWith("触发:每天 09:00"));
        JsonNode trigger = out.path("nodes").get(0);
        assertEquals("TRIGGER", trigger.path("type").asText());
        assertEquals("0 0 9 * * ?", trigger.path("config").path("cron").asText());
        assertEquals(21L, trigger.path("config").path("audienceId").asLong());

        JsonNode action = out.path("nodes").get(1);
        assertEquals("ACTION", action.path("type").asText());
        assertEquals("sms", action.path("config").path("channel").asText());
        assertEquals(11L, action.path("config").path("templateId").asLong());

        assertTrue(out.path("audienceHint").path("matched").asBoolean());
        assertEquals("近30天未购买会员", out.path("audienceHint").path("audienceName").asText());
        assertEquals(1.0, out.path("confidence").asDouble());
    }

    @Test
    void weeklyMondayWithDelayBuildsChain() {
        ObjectNode input = context();
        input.put("prompt", "每周一下午3点向新注册用户发送邮件,使用 新用户欢迎邮件 模板,延迟2天后再次提醒");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        assertEquals("SUCCESS", outcome.status());

        JsonNode nodes = outcome.output().path("nodes");
        assertEquals("0 0 15 ? * 2", nodes.get(0).path("config").path("cron").asText());
        JsonNode delay = nodes.get(1);
        assertEquals("DELAY", delay.path("type").asText());
        assertEquals(2 * 24 * 60, delay.path("config").path("minutes").asLong());
        assertEquals("action_email", nodes.get(2).path("key").asText());
        assertEquals("END", nodes.get(3).path("type").asText());

        JsonNode edges = outcome.output().path("edges");
        assertEquals(3, edges.size());
        assertEquals("trigger", edges.get(0).path("source").asText());
        assertEquals("delay", edges.get(0).path("target").asText());
        assertEquals("delay", edges.get(1).path("source").asText());
        assertEquals("action_email", edges.get(1).path("target").asText());
        assertEquals("action_email", edges.get(2).path("source").asText());
        assertEquals("end", edges.get(2).path("target").asText());
    }

    @Test
    void everyNDaysNotMisparsedAsHourOrDelay() {
        ObjectNode input = context();
        input.put("prompt", "每3天发送短信促销通知");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        JsonNode trigger = outcome.output().path("nodes").get(0);
        // 3 未被 TIME 误认作小时，也不产生 DELAY 节点
        assertEquals("0 0 9 */3 * ?", trigger.path("config").path("cron").asText());
        boolean hasDelay = false;
        for (JsonNode n : outcome.output().path("nodes")) {
            if ("DELAY".equals(n.path("type").asText())) {
                hasDelay = true;
            }
        }
        assertFalse(hasDelay);
    }

    @Test
    void dPlusOneDelayParsedAsDays() {
        ObjectNode input = context();
        input.put("prompt", "发送短信通知 D+1");
        JsonNode delay = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG)
                .output().path("nodes").get(1);
        assertEquals("DELAY", delay.path("type").asText());
        assertEquals(24 * 60L, delay.path("config").path("minutes").asLong());
    }

    @Test
    void unmatchedAudienceAndTemplateHintManualReview() {
        ObjectNode input = context();
        input.put("prompt", "每天向高价值客户发送专属优惠");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        JsonNode out = outcome.output();
        JsonNode hint = out.path("audienceHint");
        assertFalse(hint.path("matched").asBoolean());
        assertTrue(hint.path("suggestedName").asText().length() >= 2);
        assertTrue(hint.path("note").asText().contains("人工圈选"));

        JsonNode action = out.path("nodes").get(1);
        assertTrue(action.path("config").path("templateId").isNull());
        assertTrue(out.path("planSummary").asText().contains("保存前需人工选择"));
    }

    @Test
    void eventSemanticHintsWithoutAutoEventTrigger() {
        ObjectNode input = context();
        input.put("prompt", "用户下单后向近30天未购买会员发送短信");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        JsonNode out = outcome.output();
        assertEquals("SCHEDULED", out.path("nodes").get(0).path("config").path("triggerType").asText());
        assertTrue(out.path("planSummary").asText().contains("事件语义"));
    }

    @Test
    void defaultChannelIsSmsWhenUnspecified() {
        ObjectNode input = context();
        input.put("prompt", "每天发一条运营通知");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        JsonNode nodes = outcome.output().path("nodes");
        assertEquals("action_sms", nodes.get(1).path("key").asText());
        assertTrue(outcome.output().path("planSummary").asText().contains("默认使用短信"));
    }

    @Test
    void multiChannelOrderFollowsPrompt() {
        ObjectNode input = context();
        input.put("prompt", "先发邮件再发短信,针对新注册用户,模板分别用 新用户欢迎邮件 和 618大促通知");

        AgentOutcome outcome = AgentExecutor.run(PLANNER, PLANNER, "workflow_generate", input, CFG);
        JsonNode nodes = outcome.output().path("nodes");
        // 非延迟线性链：trigger → action_email → action_sms → end
        assertEquals("action_email", nodes.get(1).path("key").asText());
        assertEquals(12L, nodes.get(1).path("config").path("templateId").asLong());
        assertEquals("action_sms", nodes.get(2).path("key").asText());
        assertEquals(11L, nodes.get(2).path("config").path("templateId").asLong());
        assertEquals(22L, outcome.output().path("audienceHint").path("audienceId").asLong());
    }

    @Test
    void emptyContextStillProducesValidDraft() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "每天9点发短信");
        JsonNode out = PLANNER.plan(input);
        assertEquals(3, out.path("nodes").size());
        assertEquals(2, out.path("edges").size());
        assertEquals("0 0 9 * * ?", out.path("nodes").get(0).path("config").path("cron").asText());
        assertFalse(out.path("audienceHint").path("matched").asBoolean());
    }

    @Test
    void blankPromptDegradesToDefaultsNotException() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "  ");
        JsonNode out = PLANNER.fallback(input);
        assertNotNull(out);
        assertEquals("0 0 9 * * ?", out.path("nodes").get(0).path("config").path("cron").asText());
        assertEquals("action_sms", out.path("nodes").get(1).path("key").asText());
    }

    @Test
    void schemaRequiresCoreDraftFields() {
        assertTrue(PLANNER.schema().contains("planSummary"));
        assertTrue(PLANNER.schema().contains("audienceHint"));
        assertTrue(PLANNER.schema().contains("$schema"));
    }
}