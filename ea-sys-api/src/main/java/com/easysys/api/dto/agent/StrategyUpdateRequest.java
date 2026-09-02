package com.easysys.api.dto.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分层策略编辑请求（仅 draft 可编辑）：重编 layers（每层通道可用性 / 路由顺序 / 优先级），
 * 服务端重建策略文档并沿用原 strategy_version / source / fallback_rule。
 */
public record StrategyUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotEmpty @Valid List<LayerEdit> layers) {

    public record LayerEdit(
            @NotBlank @Size(max = 32) String id,
            @NotBlank @Size(max = 64) String name,
            @NotBlank String channelAvailability,
            List<String> routeOrder,
            int priority) {
    }
}