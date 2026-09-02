package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 确定性兜底：主提供方失败 / schema 不符 / 低置信时接管。
 * 必须可离线运行、绝不抛异常（实现见确定性规划器）。
 */
public interface AgentFallback {

    JsonNode fallback(JsonNode input) throws Exception;
}