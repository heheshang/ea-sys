package com.easysys.api.controller;

import com.easysys.api.dto.agent.RoutePreviewRequest;
import com.easysys.api.dto.agent.RoutePreviewView;
import com.easysys.api.dto.agent.StrategyRequest;
import com.easysys.api.dto.agent.StrategyView;
import com.easysys.api.service.RoutePreviewService;
import com.easysys.api.service.StrategyService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体接入面：分层策略 CRUD/发布闸门/生效策略 + 路由预览（小样本触达史重排）。
 */
@RestController
@RequestMapping("/api/agent")
public class StrategyController {

    private final StrategyService strategyService;
    private final RoutePreviewService routePreviewService;

    public StrategyController(StrategyService strategyService, RoutePreviewService routePreviewService) {
        this.strategyService = strategyService;
        this.routePreviewService = routePreviewService;
    }

    @GetMapping("/strategies")
    public ApiResponse<List<StrategyView>> list() {
        return ApiResponse.ok(strategyService.list());
    }

    @PostMapping("/strategies")
    public ApiResponse<StrategyView> generate(@Valid @RequestBody StrategyRequest req,
                                              @RequestAttribute String username) {
        return ApiResponse.ok(strategyService.generate(req, username));
    }

    @GetMapping("/strategies/active")
    public ApiResponse<StrategyView> active() {
        return ApiResponse.ok(strategyService.getActive());
    }

    @GetMapping("/strategies/{id}")
    public ApiResponse<StrategyView> get(@PathVariable Long id) {
        return ApiResponse.ok(strategyService.get(id));
    }

    @PostMapping("/strategies/{id}/publish")
    public ApiResponse<StrategyView> publish(@PathVariable Long id) {
        return ApiResponse.ok(strategyService.publish(id));
    }

    @DeleteMapping("/strategies/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        strategyService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 路由预览：近 24h 触达史重排（route_split 前的确定性预估）。 */
    @PostMapping("/route-preview")
    public ApiResponse<RoutePreviewView> routePreview(@Valid @RequestBody RoutePreviewRequest req) {
        return ApiResponse.ok(routePreviewService.preview(req.contactId(), req.routeOrder()));
    }
}