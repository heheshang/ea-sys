package com.easysys.agent;

/**
 * 智能体类型（审计与执行封装共用）。
 * LAYER：人群级分层策略制定；ROUTER：单用户通道决策；CHURN：流失风险预警；WORKFLOW：工作流生成。
 */
public enum AgentType {
    LAYER,
    ROUTER,
    CHURN,
    WORKFLOW
}