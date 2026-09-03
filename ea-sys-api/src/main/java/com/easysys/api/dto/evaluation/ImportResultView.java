package com.easysys.api.dto.evaluation;

import java.util.List;

/** jsonl 数据集导入结果：成功/跳过/错误行号列表（坏行跳过不整批失败）。 */
public record ImportResultView(
        int imported,
        int skipped,
        List<LineError> errors) {

    /** 单行解析错误（行号从 1 计）。 */
    public record LineError(int line, String message) {
    }
}