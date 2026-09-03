package com.easysys.api.config;

import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.engine.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e) {
        return ResponseEntity.status(httpStatus(e.code())).body(ApiResponse.error(e.code(), e.getMessage()));
    }

    private static HttpStatus httpStatus(int code) {
        if (code == ErrorCode.UNAUTHORIZED) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == ErrorCode.NOT_FOUND) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.BAD_REQUEST;
    }

    /** 引擎校验/执行异常 → 400，message 直接透出（条件 DSL 非法、流非法等）。 */
    @ExceptionHandler(EngineException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> engine(EngineException e) {
        return ApiResponse.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /** 直接抛出的 HTTP 状态异常（如回调鉴权 401、参数 400）→ 保留状态码透出。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException e) {
        int code = e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                ? ErrorCode.UNAUTHORIZED : ErrorCode.BAD_REQUEST;
        String message = e.getReason() == null ? "请求错误" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数错误");
        return ApiResponse.error(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> other(Exception e) {
        log.error("unhandled exception", e);
        return ApiResponse.error(ErrorCode.INTERNAL, "系统异常");
    }
}