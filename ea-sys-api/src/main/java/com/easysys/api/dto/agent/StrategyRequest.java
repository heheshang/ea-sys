package com.easysys.api.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分层策略生成请求：确定性规则规划器入参（strategy_generate 审计动作）。
 * route_order 缺省 ["sms","email"]；通道枚举 sms / email。
 */
public record StrategyRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) String strategyVersion,
        List<String> routeOrder) {
}