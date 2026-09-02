package com.easysys.api.dto.audience;

/**
 * 批量创建结果：created 成功数，skipped 因随机碰撞被跳过的数（正常为 0）。
 */
public record BatchContactCreateResult(int created, int skipped) {
}