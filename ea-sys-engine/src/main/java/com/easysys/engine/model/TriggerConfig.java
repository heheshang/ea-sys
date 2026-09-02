package com.easysys.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * TRIGGER 节点触发配置（node.config JSONB）。
 * <p>triggerType 缺失时按 {@link TriggerType#MANUAL} 处理，兼容历史画布（无触发配置的默认手动批跑）。
 * 各触发模式从同一份 config 读取：
 * <ul>
 *   <li>SCHEDULED → cron + timezone + audienceId（每次触发重新圈选该人群快照）</li>
 *   <li>EVENT → eventName + 可选 eventFilter</li>
 *   <li>IMMEDIATE → audienceId（发布成功后立即圈选该人群快照执行一次）</li>
 *   <li>MANUAL / API → 无需额外配置</li>
 * </ul>
 */
public record TriggerConfig(
        String triggerType,
        String cron,
        String timezone,
        Long audienceId,
        String eventName,
        JsonNode eventFilter) {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    public static TriggerConfig of(JsonNode cfg) {
        if (cfg == null || cfg.isNull() || cfg.isMissingNode()) {
            cfg = JsonNodeFactory.instance.objectNode();
        }
        String tt = cfg.path("triggerType").asText(null);
        if (tt == null || tt.isBlank()) {
            tt = TriggerType.MANUAL.name();
        }
        Long audienceId = cfg.path("audienceId").isNumber() ? cfg.path("audienceId").asLong() : null;
        JsonNode filter = cfg.get("eventFilter");
        String tz = cfg.path("timezone").asText(DEFAULT_TIMEZONE);
        if (tz == null || tz.isBlank()) {
            tz = DEFAULT_TIMEZONE;
        }
        return new TriggerConfig(tt, cfg.path("cron").asText(null), tz, audienceId,
                cfg.path("eventName").asText(null), filter);
    }

    public boolean isScheduled() {
        return TriggerType.SCHEDULED.name().equals(triggerType);
    }

    public boolean isEvent() {
        return TriggerType.EVENT.name().equals(triggerType);
    }

    public boolean isImmediate() {
        return TriggerType.IMMEDIATE.name().equals(triggerType);
    }
}