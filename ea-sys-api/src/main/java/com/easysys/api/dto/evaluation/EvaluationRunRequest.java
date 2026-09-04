package com.easysys.api.dto.evaluation;

import java.util.List;

/** 评测运行请求：数据集 + 评测器子集（缺省 = 全量 16 个内置评测器）+ LLM 判分轮次 + 绑定版本。 */
public record EvaluationRunRequest(
        Long datasetId,
        List<String> evaluators,
        /** LLM 判分轮次 1-5（多次取均值；缺省 1；仅 llm_judge 类评测器参与）。 */
        Integer judgeRounds,
        /** 显式绑定数据集版本 id（缺省 = 数据集最新已发布版本；无版本则回退实时用例）。 */
        Long datasetVersionId) {
}