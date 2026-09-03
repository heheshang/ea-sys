package com.easysys.api.service;

import com.easysys.api.mapper.LlmUsageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM 用量记账（驾驶舱 LLM 卡数据源）：模型调用/提问轮次 upsert + 近 7 天聚合查询。
 * 采集为旁路遥测 —— 任何写入失败只告警，绝不影响主链路（agent 调用/SSE 流）。
 */
@Service
public class LlmUsageService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageService.class);

    private final LlmUsageMapper llmUsageMapper;

    public LlmUsageService(LlmUsageMapper llmUsageMapper) {
        this.llmUsageMapper = llmUsageMapper;
    }

    /** 模型调用记账：调用方保证 tenantId 非空、usage>0（真实 LLM）。context 为本次输入构成 JSON。 */
    public void recordCall(Long tenantId, String agentType, String sessionId,
                           long inputTokens, long outputTokens, long cachedTokens, String context) {
        try {
            llmUsageMapper.upsertCall(tenantId, agentType, sessionId,
                    inputTokens, outputTokens, cachedTokens, context);
        } catch (Exception e) {
            log.warn("llm_usage 模型调用记账失败 (tenant={}, agent={}): {}", tenantId, agentType, e.getMessage());
        }
    }

    /** 聊天提问轮次 +1（ai-chat 请求入口；批处理不算轮次）。 */
    public void markRound(Long tenantId, String agentType, String sessionId) {
        try {
            llmUsageMapper.markRound(tenantId, agentType, sessionId);
        } catch (Exception e) {
            log.warn("llm_usage 轮次记账失败 (tenant={}, agent={}): {}", tenantId, agentType, e.getMessage());
        }
    }

    /** 近 7 天全部通道用量聚合：{calls, rounds, input_tokens, output_tokens, cached_tokens}。 */
    public Map<String, Object> aggregate(Long tenantId) {
        return llmUsageMapper.selectAggregate(tenantId);
    }

    /** 近 7 天最近一次对话 LLM 调用的输入构成 JSON（快照兜底；null = 无对话调用）。 */
    public String lastChatContext(Long tenantId) {
        return llmUsageMapper.selectLastChatContext(tenantId);
    }

    /** 近 7 天最近一次聊天会话 {@code {agent_type, session_id}}（null = 无聊天会话）。 */
    public Map<String, Object> lastChatSession(Long tenantId) {
        return llmUsageMapper.selectLastChatSession(tenantId);
    }
}