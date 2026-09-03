package com.easysys.api.dto.evaluation;

import java.util.List;

/** 评测运行请求：数据集 + 评测器子集（缺省 = 全量 15 个内置评测器）+ LLM 判分轮次。 */
public record EvaluationRunRequest(
        Long datasetId,
        List<String> evaluators,
        /** LLM 判分轮次 1-5（多次取均值；缺省 1；仅 llm_judge 类评测器参与）。 */
        Integer judgeRounds) {
}