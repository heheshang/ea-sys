package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 评测任务详情视图：任务基础信息 + 逐样本聚合出的各指标 breakdown
 * （均值/通过数/适用数，LLM-Judge 多轮另有标准差/平均绝对偏差，LangSmith 风格）。
 */
public record TaskDetailView(
        TaskView task,
        JsonNode metrics) {
}