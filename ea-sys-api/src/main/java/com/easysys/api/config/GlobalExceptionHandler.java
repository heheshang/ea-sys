package com.easysys.api.config;

import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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