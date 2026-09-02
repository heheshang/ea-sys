package com.easysys.agent;

/**
 * 智能体结构化输出 JSON Schema（Draft-07）。
 * 分层策略文档与单用户路由决策各自成 schema；确定性规划器输出必须自洽通过，
 * LLM 接入后输出硬校验以同一 schema 执行（不符 → 确定性降级）。
 */
public final class LayerSchemas {

    private LayerSchemas() {
    }

    /**
     * 分层策略文档 schema：L1..Ln 层定义 + fallback_rule + 顶层置信度。
     * rule 仅支持 `channel_availability` 维度（sms_only / email_only / multi / none）。
     */
    public static String strategySchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["strategy_version", "dimensions", "layers", "fallback_rule", "source", "confidence"],
                  "properties": {
                    "strategy_version": { "type": "string", "minLength": 1 },
                    "dimensions": { "type": "array", "items": { "type": "string" } },
                    "layers": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "object",
                        "required": ["id", "name", "rule", "route_order", "priority", "confidence"],
                        "properties": {
                          "id": { "type": "string", "minLength": 1 },
                          "name": { "type": "string" },
                          "rule": {
                            "type": "object",
                            "required": ["channel_availability"],
                            "properties": {
                              "channel_availability": {
                                "enum": ["sms_only", "email_only", "multi", "none"]
                              }
                            }
                          },
                          "route_order": { "type": "array", "items": { "enum": ["sms", "email"] } },
                          "priority": { "type": "integer" },
                          "confidence": { "type": "number" },
                          "rationale": { "type": "string" }
                        }
                      }
                    },
                    "fallback_rule": {
                      "type": "object",
                      "required": ["channel_availability", "route_order"],
                      "properties": {
                        "channel_availability": { "enum": ["sms_only", "email_only", "multi", "none"] },
                        "route_order": { "type": "array", "items": { "enum": ["sms", "email"] } }
                      }
                    },
                    "source": { "enum": ["deterministic", "llm"] },
                    "auditable": { "type": "boolean" },
                    "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                  }
                }
                """;
    }

    /**
     * 单用户路由决策 schema：分层结果 + 通道顺序重排 + 跳过标记（ROUTER 输出约束）。
     */
    public static String routeDecisionSchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["layer", "channels", "route_order", "skip", "confidence"],
                  "properties": {
                    "layer": { "type": "string" },
                    "channels": { "type": "array", "items": { "enum": ["sms", "email"] } },
                    "route_order": { "type": "array", "items": { "enum": ["sms", "email"] } },
                    "skip": { "type": "boolean" },
                    "skip_reason": { "type": ["string", "null"] },
                    "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                  }
                }
                """;
    }
}