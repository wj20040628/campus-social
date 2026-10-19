package com.campus.social.exception;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
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
    public ResponseEntity<Result<?>> business(BusinessException e) {
        log.warn("BusinessException: code={}, msg={}", e.getCode(), e.getMessage());
        // 真实 HTTP 状态码:登记表内的码走映射(SPEC §9 AC-04 要 429、§12 步骤 3.4 要 403);
        // 未登记的 1xxx 业务码保持 HTTP 200,零回归风险
        HttpStatus status = BusinessHttpStatus.resolve(e.getCode());
        if (status == null) {
            return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 参数校验:@Valid 触发的字段级错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> methodNotValid(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("Param validation failed: {}", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "参数校验失败: " + fieldErrors));
    }

    /** @RequestParam 缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> missingParam(MissingServletRequestParameterException e) {
        log.warn("Missing param: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "缺少参数: " + e.getParameterName()));
    }

    /** 类型不匹配(如 id 传 abc) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> typeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch: {} = {}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "参数类型错误: " + e.getName()));
    }

    /**
     * 请求体 JSON 解析失败（字段类型对不上、JSON 语法错、枚举值不存在）。
     *
     * <p>不接住它就会落进最后的兜底分支变成 500，前端弹「服务器内部错误」，
     * 而真实原因是**客户端把字段类型传错了** —— 前端会先去查服务端日志，
     * 排查方向完全跑偏。实测踩过：反馈附件的 {@code type} 传字符串
     * {@code "img"} 而 DTO 要 Integer，Jackson 抛本异常，
     * 表现却是「上传了照片 → 服务器内部错误」，完全看不出是参数问题。
     *
     * <p>回 400 且 message 里带上字段路径，调用方能直接定位到是哪一项。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> notReadable(HttpMessageNotReadableException e) {
        log.warn("Request body not readable: {}", rootMessage(e));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "请求体格式错误: " + rootMessage(e)));
    }

    /** 只取根因消息:Jackson 的包装层信息是「JSON parse error: ...」这类模板,太长且无用 */
    private String rootMessage(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String msg = cause.getMessage();
        if (msg == null) {
            return "字段类型不匹配";
        }
        int at = msg.indexOf(" at [Source");
        if (at > 0) {
            msg = msg.substring(0, at);
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    /** 404 资源不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> notFound(NoResourceFoundException e) {
        log.info("404: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ResultCode.NOT_FOUND));
    }

    /**
     * 路径存在但没有匹配的处理器（Spring 6.1 起与 NoResourceFoundException 分开抛）。
     *
     * <p>不单独兜的话它会落进最后的兜底分支变成 500：
     * 前端拿到 5xx 会去查服务端日志，而真实原因是「这个 URL 写错了」，
     * 排查方向完全跑偏。路由不存在是客户端问题，回 404。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> noHandler(NoHandlerFoundException e) {
        log.info("404 no handler: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ResultCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ResultCode.METHOD_NOT_ALLOWED));
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
