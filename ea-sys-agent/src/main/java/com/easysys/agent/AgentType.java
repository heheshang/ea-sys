package com.easysys.agent;

/**
 * 智能体类型（审计与执行封装共用）。
 * LAYER：人群级分层策略制定；ROUTER：单用户通道决策；CHURN：流失风险预警；WORKFLOW：工作流生成；
 * COCKPIT：驾驶舱洞察（LLM 调用监控总览 + 图谱状态健康分析）；EVALUATION：评测中心报告（评测器聚合/分级发现/准确率）。
 */
public enum AgentType {
    LAYER,
    ROUTER,
    CHURN,
    WORKFLOW,
    COCKPIT,
    EVALUATION
}