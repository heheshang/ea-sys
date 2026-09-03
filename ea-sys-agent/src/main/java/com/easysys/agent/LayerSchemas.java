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

    /**
     * 流失风险批量评估 schema（churn_scan 主路径）：成员明细 + 聚合 summary，框架层整体校验。
     */
    public static String churnScanSchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["results", "summary"],
                  "properties": {
                    "results": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["contact_id", "churn_risk", "tier", "drivers"],
                        "properties": {
                          "contact_id": { "type": "integer" },
                          "churn_risk": { "type": "integer", "minimum": 0, "maximum": 100 },
                          "tier": { "enum": ["HIGH", "MEDIUM", "LOW"] },
                          "drivers": { "type": "array", "items": { "type": "string" } }
                        },
                        "additionalProperties": false
                      }
                    },
                    "summary": {
                      "type": "object",
                      "required": ["scanned", "HIGH", "MEDIUM", "LOW"],
                      "properties": {
                        "scanned": { "type": "integer" },
                        "HIGH": { "type": "integer" },
                        "MEDIUM": { "type": "integer" },
                        "LOW": { "type": "integer" }
                      }
                    }
                  },
                  "additionalProperties": false
                }
                """;
    }

    /**
     * 流失风险评估 schema：风险分 0-100 + 等级 + 驱动因子（CHURN 输出约束，LLM 接入后硬校验）。
     */
    public static String churnRiskSchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["churn_risk", "tier", "drivers"],
                  "properties": {
                    "churn_risk": { "type": "integer", "minimum": 0, "maximum": 100 },
                    "tier": { "enum": ["HIGH", "MEDIUM", "LOW"] },
                    "drivers": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "流失驱动因子（如：30天未活跃、连续2周无会话）"
                    }
                  },
                  "additionalProperties": false
                }
                """;
    }

    /**
     * 驾驶舱洞察 schema（cockpit_insights 输出约束）：健康分 + 洞察列表。
     * insights 无条件至少一条（无异常也产「运行正常」info 项，minItems=1）。
     */
    public static String cockpitInsightSchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["generated_at", "overall_health", "insights"],
                  "properties": {
                    "generated_at": { "type": "string" },
                    "overall_health": { "type": "number", "minimum": 0, "maximum": 100 },
                    "insights": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "object",
                        "required": ["type", "severity", "title", "detail"],
                        "properties": {
                          "type": { "enum": ["anomaly", "trend", "recommendation", "info"] },
                          "severity": { "enum": ["info", "warning", "critical"] },
                          "title": { "type": "string" },
                          "detail": { "type": "string" },
                          "metric_ref": { "type": "string" },
                          "suggestion": { "type": "string" }
                        }
                      }
                    },
                    "strategy_version": { "type": "string", "minLength": 1 },
                    "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                  }
                }
                """;
    }

    /**
     * 评测报告 schema（evaluation_run 输出约束）：指标均值 + 分级发现 + 汇总 verdict + 置信度。
     * metrics 为选中评测器均值；findings 按阈值产 INFO/WARNING/BLOCKED；summary.score 0-100。
     */
    public static String evaluationReportSchema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["report_type", "scope", "mode", "tested_cases", "metrics",
                                "findings", "summary", "confidence", "generated_at"],
                  "properties": {
                    "report_type": { "enum": ["evaluation_report"] },
                    "scope": { "type": "string" },
                    "mode": { "enum": ["openjudge", "execute"] },
                    "tested_cases": { "type": "integer", "minimum": 0 },
                    "metrics": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["metric", "category", "avg_score", "passed_count"],
                        "properties": {
                          "metric": { "type": "string" },
                          "category": { "enum": ["rule", "llm_judge"] },
                          "avg_score": { "type": "number", "minimum": 0, "maximum": 1 },
                          "passed_count": { "type": "integer", "minimum": 0 },
                          "applicable_count": { "type": "integer", "minimum": 0 }
                        }
                      }
                    },
                    "findings": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["level", "dimension", "detail"],
                        "properties": {
                          "level": { "enum": ["INFO", "WARNING", "BLOCKED"] },
                          "dimension": { "type": "string" },
                          "detail": { "type": "string" },
                          "suggestion": { "type": "string" }
                        }
                      }
                    },
                    "summary": {
                      "type": "object",
                      "required": ["score", "verdict"],
                      "properties": {
                        "score": { "type": "number", "minimum": 0, "maximum": 100 },
                        "verdict": { "enum": ["PASS", "WARN", "FAIL"] }
                      }
                    },
                    "strategy_version": { "type": "string", "minLength": 1 },
                    "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
                    "generated_at": { "type": "string" }
                  }
                }
                """;
    }
}