package com.easysys.api.controller;

import com.easysys.api.dto.retention.ChannelEffectView;
import com.easysys.api.dto.retention.FunnelView;
import com.easysys.api.dto.retention.IntervalRetentionView;
import com.easysys.api.dto.retention.WorkflowEffectView;
import com.easysys.api.service.RetentionService;
import com.easysys.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * 留存看板：转化漏斗、区间留存曲线、渠道效果、工作流效果对比。
 */
@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    /** 转化漏斗：圈选 → 执行 → 触达；workflowId 空 = 租户全量。 */
    @GetMapping("/funnel")
    public ApiResponse<FunnelView> funnel(@RequestParam(required = false) Long workflowId) {
        return ApiResponse.ok(retentionService.funnel(workflowId));
    }

    /** 区间留存（N 天双窗口留存率）：days ∈ {7,30,90}。 */
    @GetMapping("/interval")
    public ApiResponse<IntervalRetentionView> interval(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(retentionService.intervalRetention(days));
    }

    /** 渠道效果：近 N 天送达率/失败/触达规模；eventName 可选统计转化。 */
    @GetMapping("/channel-effect")
    public ApiResponse<ChannelEffectView> channelEffect(@RequestParam(defaultValue = "7") int days,
                                                        @RequestParam(required = false) String eventName) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        return ApiResponse.ok(retentionService.channelEffect(since, eventName));
    }

    /** 工作流效果：每工作流最近一次执行的触达规模 + N 天留存。 */
    @GetMapping("/workflows")
    public ApiResponse<WorkflowEffectView> workflows(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(retentionService.workflowEffect(days));
    }
}