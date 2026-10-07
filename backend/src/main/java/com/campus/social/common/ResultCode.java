package com.campus.social.common;

/**
 * 统一返回码枚举
 * <p>code=200 为成功；其余按语义分桶：
 * 4xx 客户端错误 / 5xx 服务端错误
 */
public enum ResultCode {

    SUCCESS(200, "成功"),

    // 4xx 客户端
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或 token 失效"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "方法不允许"),
    CONFLICT(409, "资源冲突(如重复注册)"),
    PAYLOAD_TOO_LARGE(413, "上传文件过大"),
    UNPROCESSABLE(422, "业务校验失败"),

    // 5xx 服务端
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // 业务码 (1xxx)
    USER_FROZEN(1001, "账号已冻结"),
    USER_CANCELLED(1002, "账号已注销"),
    USERNAME_EXISTS(1003, "用户名已存在"),
    STUDENT_NO_EXISTS(1004, "学号已被使用"),
    WRONG_PASSWORD(1005, "用户名或密码错误"),
    REFRESH_TOKEN_INVALID(1006, "refresh token 失效,请重新登录"),
    REFRESH_TOKEN_REUSED(1007, "refresh token 已被重用,拒绝"),
    SENSITIVE_WORD_BLOCKED(1008, "内容包含违规词,拒绝发布"),
    POST_NOT_FOUND(1009, "帖子不存在或已下架"),
    POST_REVIEWING(1010, "帖子审核中,未进入 Feed"),
    NOT_POST_AUTHOR(1011, "非帖子作者,无法操作"),
    ALREADY_FOLLOED(1012, "已关注,请勿重复"),
    BLACKLISTED(1013, "你被对方拉黑或拉黑了对方"),
    CHAT_SELF(1014, "不能给自己发消息"),
    ACTIVITY_FULL(1015, "活动名额已满"),
    ACTIVITY_CANCELLED(1016, "活动已取消"),
    ACTIVITY_OVER(1017, "活动已结束"),
    NOT_CIRCLE_OWNER(1018, "仅圈子创建者/管理员可操作"),
    ALREADY_IN_CIRCLE(1019, "已在该圈子"),
    CODE_INVALID(1020, "验证码错误或已过期"),
    CODE_TOO_FREQUENT(1021, "验证码发送过于频繁,请稍后再试"),
    CODE_LIMIT_REACHED(1022, "今日验证码发送次数已达上限"),
    PHONE_NOT_REGISTERED(1023, "该手机号未注册"),
    EMAIL_NOT_REGISTERED(1024, "该邮箱未注册"),
    NOT_ADMIN(1025, "需要管理员权限"),
    NOT_GROUP_MEMBER(1026, "你不是该群成员"),
    NOT_GROUP_OWNER(1027, "仅群主可操作"),
    GROUP_NAME_EMPTY(1028, "群名称不能为空");


    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
