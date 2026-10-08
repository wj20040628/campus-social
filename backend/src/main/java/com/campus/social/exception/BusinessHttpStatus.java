package com.campus.social.exception;

import com.campus.social.common.ResultCode;
import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * 业务码 → HTTP 状态码映射表。
 *
 * <p>存在的理由：改造前 {@code GlobalExceptionHandler.business()} 对业务异常一律返回
 * HTTP 200，错误只体现在响应体的 code 里。而 SPEC §9 AC-04 要求 429、
 * §12 步骤 3.4 要求 403，两者冲突，故按 openapi.yaml §4 的契约改为真实 HTTP 状态码。
 *
 * <p>为什么只映射这些项：其余 1xxx 业务码保持 HTTP 200 不变，零回归风险。
 * 前端两条路径都拿得到文案——成功分支判 {@code resp.data.code === 200}，
 * 错误分支读 {@code error.response.data.message}。
 *
 * <p>新增业务码时若也需要真实 HTTP 状态，必须在此登记，否则会静默退回 HTTP 200。
 */
public final class BusinessHttpStatus {

    private static final Map<ResultCode, HttpStatus> MAPPING = new EnumMap<>(ResultCode.class);

    static {
        // 4xx 客户端错误段:与 HTTP 语义一一对应
        MAPPING.put(ResultCode.BAD_REQUEST, HttpStatus.BAD_REQUEST);
        MAPPING.put(ResultCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ResultCode.FORBIDDEN, HttpStatus.FORBIDDEN);
        MAPPING.put(ResultCode.NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ResultCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED);
        MAPPING.put(ResultCode.CONFLICT, HttpStatus.CONFLICT);
        MAPPING.put(ResultCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE);
        MAPPING.put(ResultCode.UNPROCESSABLE, HttpStatus.UNPROCESSABLE_ENTITY);
        MAPPING.put(ResultCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);

        // 账号状态类:一律 403(不是 401——凭证有效,是账号不可用)
        MAPPING.put(ResultCode.USER_FROZEN, HttpStatus.FORBIDDEN);
        MAPPING.put(ResultCode.USER_CANCELLED, HttpStatus.FORBIDDEN);
        MAPPING.put(ResultCode.BLACKLISTED, HttpStatus.FORBIDDEN);
        MAPPING.put(ResultCode.NOT_ADMIN, HttpStatus.FORBIDDEN);

        // F2 认证类
        MAPPING.put(ResultCode.USER_NOT_VERIFIED, HttpStatus.FORBIDDEN);
        MAPPING.put(ResultCode.CAMPUS_EMAIL_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_ENTITY);
        MAPPING.put(ResultCode.VERIFY_ALREADY_DONE, HttpStatus.CONFLICT);
        MAPPING.put(ResultCode.VERIFY_ALREADY_PENDING, HttpStatus.CONFLICT);
        MAPPING.put(ResultCode.STUDENT_NO_EXISTS, HttpStatus.CONFLICT);

        // F3 搭话护栏
        MAPPING.put(ResultCode.STRANGER_MSG_CLOSED, HttpStatus.FORBIDDEN);

        // F5 公告
        MAPPING.put(ResultCode.ANNOUNCEMENT_NOT_FOUND, HttpStatus.NOT_FOUND);

        // F4 举报
        MAPPING.put(ResultCode.REPORT_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ResultCode.REPORT_ALREADY_HANDLED, HttpStatus.CONFLICT);

        // 验证码限频:openapi 的 /user/verify/campus-email 明确要求 429
        MAPPING.put(ResultCode.CODE_TOO_FREQUENT, HttpStatus.TOO_MANY_REQUESTS);
        MAPPING.put(ResultCode.CODE_LIMIT_REACHED, HttpStatus.TOO_MANY_REQUESTS);

        // 字典 id 失效:422 而非 404。请求本身格式正确,是取值在业务上不可用
        // (不存在 / 已停用 / 不属于该校),与「资源不存在」语义不同
        MAPPING.put(ResultCode.DEPT_INVALID, HttpStatus.UNPROCESSABLE_ENTITY);
        MAPPING.put(ResultCode.GRADE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY);

        // 字段已下线:400 而非 422。请求体里出现了不该存在的 key,
        // 属于「请求本身写错了」,和「取值在业务上不可用」是两类问题
        MAPPING.put(ResultCode.PROFILE_FIELD_RETIRED, HttpStatus.BAD_REQUEST);
    }

    private BusinessHttpStatus() {
    }

    /** 未登记的业务码返回 null，由调用方决定回落策略 */
    public static HttpStatus resolve(ResultCode rc) {
        return MAPPING.get(rc);
    }

    public static HttpStatus resolve(int code) {
        for (ResultCode rc : ResultCode.values()) {
            if (rc.getCode() == code) {
                return MAPPING.get(rc);
            }
        }
        return null;
    }
}
