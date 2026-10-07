package com.campus.social.exception;

import com.campus.social.common.ResultCode;

/**
 * 业务异常（由 GlobalExceptionHandler 捕获并转 Result）
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode rc) {
        this(rc.getCode(), rc.getMessage());
    }

    public BusinessException(ResultCode rc, String message) {
        this(rc.getCode(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
