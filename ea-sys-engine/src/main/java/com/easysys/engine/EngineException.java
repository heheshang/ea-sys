package com.easysys.engine;

/**
 * 引擎域异常：DAG 校验 / 条件编译 / 干跑执行失败。
 * API 层 GlobalExceptionHandler 映射为 400 业务错误。
 */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}