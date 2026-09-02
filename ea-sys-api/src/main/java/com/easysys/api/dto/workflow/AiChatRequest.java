package com.easysys.api.dto.workflow;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话式创建工作流请求体（POST /api/workflows/ai-chat，SSE 流式响应）。
 *
 * @param message  用户输入文本；确认/取消步骤时传「确认生成」/「取消」等文案
 * @param sessionId 前端生成的会话 id（crypto.randomUUID()），服务端按会话持久化历史
 * @param confirm   HITL 确认闸门结果：{@code null} 为普通消息；非空表示对挂起
 *                  plan_workflow 请求的人工决定（confirmed=true 执行 / false 取消）
 */
public record AiChatRequest(
        @NotBlank(message = "消息不能为空") String message,
        @NotBlank(message = "会话 id 不能为空") String sessionId,
        Confirm confirm) {

    public record Confirm(boolean confirmed) {
    }
}