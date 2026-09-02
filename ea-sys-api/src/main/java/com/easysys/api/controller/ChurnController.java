package com.easysys.api.controller;

import com.easysys.api.dto.agent.ChurnScanRequest;
import com.easysys.api.dto.agent.ChurnScanView;
import com.easysys.api.service.ChurnService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流失预警 Agent 接入面：对快照成员批量评估流失风险并回写标记（规则版「N 天未活跃 = HIGH」）。
 */
@RestController
@RequestMapping("/api/agent/churn")
public class ChurnController {

    private final ChurnService churnService;

    public ChurnController(ChurnService churnService) {
        this.churnService = churnService;
    }

    /** 批量扫描快照成员流失风险（churn_scan：schema 校验 + 审计 + 回写 churn_risk）。 */
    @PostMapping("/scan")
    public ApiResponse<ChurnScanView> scan(@Valid @RequestBody ChurnScanRequest req,
                                           @RequestAttribute String username) {
        return ApiResponse.ok(churnService.scan(req, username));
    }
}