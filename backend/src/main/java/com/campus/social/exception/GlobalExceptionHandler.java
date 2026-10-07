package com.campus.social.exception;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一转 Result,不裸抛、不泄堆栈
 *
 * 注:本项目用自定义 AuthInterceptor(JWT)而非 Spring Security,
 * 鉴权失败由拦截器直接写 401 响应,不经过本处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<?> business(BusinessException e) {
        log.warn("BusinessException: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验:@Valid 触发的字段级错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> methodNotValid(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("Param validation failed: {}", fieldErrors);
        return Result.fail(ResultCode.BAD_REQUEST, "参数校验失败: " + fieldErrors);
    }

    /** @RequestParam 缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> missingParam(MissingServletRequestParameterException e) {
        log.warn("Missing param: {}", e.getParameterName());
        return Result.fail(ResultCode.BAD_REQUEST, "缺少参数: " + e.getParameterName());
    }

    /** 类型不匹配(如 id 传 abc) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> typeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch: {} = {}", e.getName(), e.getValue());
        return Result.fail(ResultCode.BAD_REQUEST, "参数类型错误: " + e.getName());
    }

    /** 404 资源不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> notFound(NoResourceFoundException e) {
        log.info("404: {}", e.getResourcePath());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMethod());
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /** 兜底:未知异常(500) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unknown(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "服务器内部错误,请稍后重试"));
    }
}
