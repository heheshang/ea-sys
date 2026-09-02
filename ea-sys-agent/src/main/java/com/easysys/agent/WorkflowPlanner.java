package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WORKFLOW 智能体确定性实现：把自然语言运营需求解析为工作流 DAG 草稿 JSON。
 *
 * <p>无状态、幂等、可离线运行；输入自带租户上下文快照（templates / audiences / channels，
 * 由调用方（AiWorkflowService）先经工具执行后注入），本规划器只做纯解析，不触碰存储。
 * 输出为「草稿」语义：未匹配到模板/人群时相应字段留空并在 planSummary 中标注
 * 「保存前需人工确认」——最终保存/发布仍走既有的人工审核闸门。</p>
 *
 * <p>解析能力（第一版）：<ul>
 *   <li>通道：sms / email / push / wecom（按 prompt 出现顺序，缺省 sms）</li>
 *   <li>触发：每天/每周 X/每 N 天/每月 + 时间点 → Quartz cron（SCHEDULED）</li>
 *   <li>延迟：D+N / 延迟 N 天 / N 天后 / 延迟 N 小时 → DELAY 节点</li>
 *   <li>模板/人群：按中文二元词与租户数据匹配，未命中留空并提示</li>
 *   <li>事件语义（下单/注册/支付…）：仅提示，不自动生成 EVENT（防猜错 eventName）</li>
 * </ul></p>
 */
public final class WorkflowPlanner implements StrategyAgent, AgentFallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final long MINUTES_PER_DAY = 24 * 60;

    /** [通道, 关键词（不区分大小写，任一命中即算）]，顺序仅用于缺省，实际序按 prompt 出现位置。 */
    private static final List<String[]> CHANNELS = List.of(
            new String[]{"sms", "短信 sms"},
            new String[]{"email", "邮件 email 邮箱"},
            new String[]{"push", "推送 push"},
            new String[]{"wecom", "企微 企业微信 wecom"});

    /** 时间点：必须带 点/时/冒号后缀，防「每3天」的 3 被误认作小时。 */
    private static final Pattern TIME = Pattern.compile("(上午|下午)?(\\d{1,2})(?:[:：](\\d{2})|(点|时))");
    private static final Pattern WEEKDAY = Pattern.compile("周([一二三四五六日天])");
    private static final Pattern EVERY_DAYS = Pattern.compile("每(?:隔)?(\\d+)天");
    private static final Pattern MONTHLY = Pattern.compile("每月|(\\d{1,2})号");
    /** 天延迟（分钟粒度）：D+1 / 延迟 N 天 / N 天后。 */
    private static final Pattern DAYS_DELAY = Pattern.compile("D\\s*\\+?\\s*(\\d+)|(?:延迟|等待)(\\d+)天|(\\d+)天(?:后|之后|后再|再)");
    private static final Pattern HOURS_DELAY = Pattern.compile("(?:延迟|等待)(\\d+)小时|(\\d+)小时(?:后|之后|后再|再)");
    private static final Pattern AUDIENCE_PHRASE = Pattern.compile("(?:向|对|给|针对)?([^，。；,;\\s]{2,14}?)(?:人群|用户|会员|客户)");
    private static final Pattern EVENT_WORD = Pattern.compile("(下单|注册|支付|加购|参与|点击|打开|浏览|入群)");

    @Override
    public AgentType type() {
        return AgentType.WORKFLOW;
    }

    @Override
    public String schema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["name", "description", "nodes", "edges", "planSummary", "audienceHint", "confidence"],
                  "properties": {
                    "name": { "type": "string", "maxLength": 128 },
                    "description": { "type": "string" },
                    "nodes": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["key", "type"],
                        "properties": {
                          "key": { "type": "string", "minLength": 1 },
                          "type": { "enum": ["TRIGGER", "DELAY", "ACTION", "UPDATE", "END", "CONDITION"] },
                          "name": { "type": "string" },
                          "config": { "type": ["object", "null"] }
                        }
                      }
                    },
                    "edges": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["source", "target"],
                        "properties": {
                          "source": { "type": "string" },
                          "target": { "type": "string" },
                          "condition": { "type": ["object", "null"] }
                        }
                      }
                    },
                    "planSummary": { "type": "string" },
                    "audienceHint": { "type": "object" },
                    "confidence": { "type": "number" }
                  }
                }
                """;
    }

    @Override
    public JsonNode plan(JsonNode input) {
        return build(input);
    }

    @Override
    public JsonNode fallback(JsonNode input) {
        return build(input);
    }

    private static JsonNode build(JsonNode input) {
        String prompt = input.path("prompt").asText("").trim();
        String promptLower = prompt.toLowerCase(Locale.ROOT);
        Set<String> promptBi = cjkBigrams(prompt);
        List<JsonNode> templates = arrayOf(input.path("templates"));
        List<JsonNode> audiences = arrayOf(input.path("audiences"));

        List<String> channels = channelsOf(prompt);
        boolean channelDefault = channels.isEmpty();
        if (channels.isEmpty()) {
            channels = List.of("sms");
        }

        // 模板匹配（每通道独立）
        List<TemplateHit> hits = new ArrayList<>();
        for (String ch : channels) {
            long id = bestTemplate(templates, ch, promptBi, promptLower);
            hits.add(new TemplateHit(ch, id, id > 0 ? templateName(templates, id) : null));
        }

        // 人群匹配
        long audienceId = bestAudience(audiences, promptBi, promptLower);
        String audienceName = audienceId > 0 ? audienceName(audiences, audienceId) : null;
        String audiencePhrase = audiencePhraseOf(prompt);

        TriggerSpec spec = triggerOf(prompt);
        long delayMinutes = delayMinutesOf(prompt);
        Matcher eventMatcher = EVENT_WORD.matcher(prompt);
        String eventWord = eventMatcher.find() ? eventMatcher.group(1) : null;

        ObjectNode out = MAPPER.createObjectNode();
        out.put("name", clip(prompt, 128));
        out.put("description", clip(prompt, 200));

        // 节点链：trigger → [delay] → action×N → end
        ArrayNode nodes = out.putArray("nodes");
        ObjectNode trigger = nodes.addObject();
        trigger.put("key", "trigger");
        trigger.put("type", "TRIGGER");
        trigger.put("name", "定时触发");
        ObjectNode tc = trigger.putObject("config");
        tc.put("triggerType", "SCHEDULED");
        tc.put("cron", spec.cron());
        tc.put("timezone", DEFAULT_TIMEZONE);
        if (audienceId > 0) {
            tc.put("audienceId", audienceId);
        } else {
            tc.putNull("audienceId");
        }

        if (delayMinutes > 0) {
            ObjectNode delay = nodes.addObject();
            delay.put("key", "delay");
            delay.put("type", "DELAY");
            delay.put("name", "延迟 " + delayMinutes + " 分钟");
            ObjectNode dc = delay.putObject("config");
            dc.put("minutes", delayMinutes);
        }

        for (TemplateHit h : hits) {
            ObjectNode action = nodes.addObject();
            action.put("key", "action_" + h.channel());
            action.put("type", "ACTION");
            action.put("name", channelName(h.channel()) + "触达");
            ObjectNode ac = action.putObject("config");
            ac.put("channel", h.channel());
            if (h.templateId() > 0) {
                ac.put("templateId", h.templateId());
            } else {
                ac.putNull("templateId");
            }
        }

        ObjectNode end = nodes.addObject();
        end.put("key", "end");
        end.put("type", "END");
        end.put("name", "结束");

        // 边：顺序连接
        List<String> chain = new ArrayList<>();
        chain.add("trigger");
        if (delayMinutes > 0) {
            chain.add("delay");
        }
        for (TemplateHit h : hits) {
            chain.add("action_" + h.channel());
        }
        chain.add("end");
        ArrayNode edges = out.putArray("edges");
        for (int i = 0; i + 1 < chain.size(); i++) {
            ObjectNode e = edges.addObject();
            e.put("source", chain.get(i));
            e.put("target", chain.get(i + 1));
        }

        // 计划摘要（含待人工确认项）
        List<String> parts = new ArrayList<>();
        parts.add("触发:" + spec.label());
        parts.add(audienceId > 0 ? "人群:" + audienceName
                : "人群:未匹配现有人群,保存前需人工圈选并补 audienceId");
        for (TemplateHit h : hits) {
            parts.add(h.templateId() > 0 ? ("通道" + h.channel() + ":模板《" + h.templateName() + "》")
                    : ("通道" + h.channel() + ":未匹配到模板,保存前需人工选择"));
        }
        if (delayMinutes > 0) {
            parts.add("延迟:" + delayMinutes + " 分钟");
        }
        if (channelDefault) {
            parts.add("提示:未识别到通道,默认使用短信");
        }
        if (eventWord != null) {
            parts.add("提示:检测到事件语义(" + eventWord + "),当前按定时触发生成,如需事件触发请手动调整 TRIGGER");
        }
        out.put("planSummary", String.join(";", parts));

        // 人群建议（前端引导）
        ObjectNode hint = out.putObject("audienceHint");
        if (audienceId > 0) {
            hint.put("matched", true);
            hint.put("audienceId", audienceId);
            hint.put("audienceName", audienceName);
        } else {
            hint.put("matched", false);
            hint.put("suggestedName", audiencePhrase == null ? "AI 建议人群" : audiencePhrase);
            hint.put("note", "未匹配现有人群,请先人工圈选后再保存");
        }

        out.put("confidence", 1.0);
        return out;
    }

    // ---- 解析助手 ----

    private static List<String> channelsOf(String prompt) {
        List<int[]> hits = new ArrayList<>(); // {index, channelIdx}
        String lower = prompt.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CHANNELS.size(); i++) {
            for (String kw : CHANNELS.get(i)[1].split(" ")) {
                int idx = lower.indexOf(kw);
                if (idx >= 0) {
                    hits.add(new int[]{idx, i});
                    break;
                }
            }
        }
        hits.sort((a, b) -> Integer.compare(a[0], b[0]));
        Set<String> out = new LinkedHashSet<>();
        for (int[] h : hits) {
            out.add(CHANNELS.get(h[1])[0]);
        }
        return new ArrayList<>(out);
    }

    private static TriggerSpec triggerOf(String prompt) {
        int hour = 9;
        int minute = 0;
        Matcher t = TIME.matcher(prompt);
        if (t.find()) {
            hour = Integer.parseInt(t.group(2)) % 24;
            if ("下午".equals(t.group(1))) {
                hour = (hour + 12) % 24;
            }
            if (t.group(3) != null) {
                minute = Integer.parseInt(t.group(3)) % 60;
            }
        }
        Matcher wd = WEEKDAY.matcher(prompt);
        if (wd.find()) {
            int d = weekdayOf(wd.group(1));
            String label = "每周" + wd.group(1) + " " + pad(hour) + ":" + pad(minute);
            return new TriggerSpec(String.format("0 %d %d ? * %d", minute, hour, d), label);
        }
        Matcher days = EVERY_DAYS.matcher(prompt);
        if (days.find()) {
            int n = Integer.parseInt(days.group(1));
            String label = "每 " + n + " 天 " + pad(hour) + ":" + pad(minute);
            return new TriggerSpec(String.format("0 %d %d */%d * ?", minute, hour, n), label);
        }
        if (MONTHLY.matcher(prompt).find()) {
            String label = "每月 1 号 " + pad(hour) + ":" + pad(minute);
            return new TriggerSpec(String.format("0 %d %d 1 * ?", minute, hour), label);
        }
        String label = "每天 " + pad(hour) + ":" + pad(minute);
        return new TriggerSpec(String.format("0 %d %d * * ?", minute, hour), label);
    }

    private static long delayMinutesOf(String prompt) {
        Matcher d = DAYS_DELAY.matcher(prompt);
        if (d.find()) {
            int n = d.group(1) != null ? Integer.parseInt(d.group(1))
                    : (d.group(2) != null ? Integer.parseInt(d.group(2)) : Integer.parseInt(d.group(3)));
            return n * MINUTES_PER_DAY;
        }
        Matcher h = HOURS_DELAY.matcher(prompt);
        if (h.find()) {
            int n = h.group(1) != null ? Integer.parseInt(h.group(1)) : Integer.parseInt(h.group(2));
            return n * 60L;
        }
        return 0;
    }

    /** 中文二元词集合（模板/人群/需求文本共现匹配）。 */
    private static Set<String> cjkBigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            char a = s.charAt(i);
            char b = s.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) {
                out.add(s.substring(i, i + 2));
            }
        }
        return out;
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static long bestTemplate(List<JsonNode> templates, String channel, Set<String> promptBi, String promptLower) {
        long bestId = 0;
        int bestScore = 0;
        for (JsonNode t : templates) {
            if (!channel.equals(t.path("channel").asText())) {
                continue;
            }
            String name = t.path("name").asText("");
            if (!name.isBlank() && promptLower.contains(name.toLowerCase(Locale.ROOT))) {
                return t.path("id").asLong();
            }
            int score = overlap(promptBi, cjkBigrams(name)) + overlap(promptBi, cjkBigrams(t.path("content").asText("")));
            if (score > bestScore) {
                bestScore = score;
                bestId = t.path("id").asLong();
            }
        }
        return bestId;
    }

    private static long bestAudience(List<JsonNode> audiences, Set<String> promptBi, String promptLower) {
        long bestId = 0;
        int bestScore = 0;
        for (JsonNode a : audiences) {
            String name = a.path("name").asText("");
            if (!name.isBlank() && (promptLower.contains(name.toLowerCase(Locale.ROOT)))) {
                return a.path("id").asLong();
            }
            int score = overlap(promptBi, cjkBigrams(name))
                    + overlap(promptBi, cjkBigrams(a.path("rule").asText("")));
            if (score > bestScore) {
                bestScore = score;
                bestId = a.path("id").asLong();
            }
        }
        return bestId;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String s : b) {
            if (a.contains(s)) {
                n++;
            }
        }
        return n;
    }

    private static String audiencePhraseOf(String prompt) {
        Matcher m = AUDIENCE_PHRASE.matcher(prompt);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String templateName(List<JsonNode> templates, long id) {
        for (JsonNode t : templates) {
            if (t.path("id").asLong() == id) {
                return t.path("name").asText("");
            }
        }
        return null;
    }

    private static String audienceName(List<JsonNode> audiences, long id) {
        for (JsonNode a : audiences) {
            if (a.path("id").asLong() == id) {
                return a.path("name").asText("");
            }
        }
        return null;
    }

    private static int weekdayOf(String c) {
        return switch (c) {
            case "日", "天" -> 1;
            case "一" -> 2;
            case "二" -> 3;
            case "三" -> 4;
            case "四" -> 5;
            case "五" -> 6;
            case "六" -> 7;
            default -> 2;
        };
    }

    private static String channelName(String ch) {
        return switch (ch) {
            case "sms" -> "短信";
            case "email" -> "邮件";
            case "push" -> "推送";
            case "wecom" -> "企微";
            default -> ch;
        };
    }

    private static String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<JsonNode> arrayOf(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(out::add);
        }
        return out;
    }

    private record TemplateHit(String channel, long templateId, String templateName) {
    }

    private record TriggerSpec(String cron, String label) {
    }
}