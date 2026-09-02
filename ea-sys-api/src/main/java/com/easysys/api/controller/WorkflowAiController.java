package com.easysys.api.controller;

import com.easysys.api.dto.workflow.AiGenerateRequest;
import com.easysys.api.dto.workflow.AiGenerateResponse;
import com.easysys.api.service.AiWorkflowService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 创建工作流：自然语言需求 → DAG 草稿 + 工具调用时间线。
 * 草稿不落库；人工审核后走既有保存（POST /api/workflows）链路。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowAiController {

    private final AiWorkflowService aiWorkflowService;

    public WorkflowAiController(AiWorkflowService aiWorkflowService) {
        this.aiWorkflowService = aiWorkflowService;
    }

    @PostMapping("/ai-generate")
    public ApiResponse<AiGenerateResponse> aiGenerate(@Valid @RequestBody AiGenerateRequest req,
                                                      @RequestAttribute String username) {
        return ApiResponse.ok(aiWorkflowService.generate(req, username));
    }
}