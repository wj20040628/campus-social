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
    GROUP_NAME_EMPTY(1028, "群名称不能为空"),

    // 业务码 1029 起:Phase 3 MVP(F3 搭话护栏 / F4 举报 / F5 公告 / F2 认证)
    // 编号纪律:接续现有最大值 1028 往下排,不新造区间
    USER_NOT_VERIFIED(1029, "请先完成校内认证"),
    STRANGER_MSG_CLOSED(1030, "对方已关闭陌生人消息"),
    ANNOUNCEMENT_NOT_FOUND(1031, "公告不存在或已下架"),
    CAMPUS_EMAIL_NOT_ALLOWED(1032, "该邮箱域名不是受信任的校园邮箱"),
    REPORT_NOT_FOUND(1033, "举报记录不存在"),
    REPORT_ALREADY_HANDLED(1034, "该举报已被处置,请勿重复操作"),
    TAG_NOT_FOUND(1035, "兴趣标签不存在"),
    ONBOARDING_STEP_INCOMPLETE(1036, "请先完成引导第 1 步学校与年级信息"),
    VERIFY_ALREADY_DONE(1037, "你已完成校内认证,无需重复认证"),
    VERIFY_ALREADY_PENDING(1038, "你的学号认证正在人工核验中,请耐心等待"),
    SCHOOL_PROFILE_INCOMPLETE(1039, "请先填写学校院系与年级"),

    // 业务码 1040 起:院系与年级字典(引导第 1 步从自由文本改为字典 id)
    // 编号纪律:接续现有最大值 1039 往下排。派单时说的「从 1033 起」已过期,
    // 1033-1039 被 F4 举报与 F11 引导占用,此处不重号。
    DEPT_INVALID(1040, "所选学院不存在或已停用,请重新选择"),
    GRADE_INVALID(1041, "所选年级不存在或已停用,请重新选择"),

    // 单独一个码而不复用 1040/1041:那两条说的是「你传的 id 不存在」,
    // 这条说的是「你传的字段本身已下线」。调用方要能据此区分是改参数还是改接口。
    // 之所以必须显式报错而不是静默忽略:静默忽略会让调用方以为写入成功,
    // 实际数据没落库,这类「以为成功」的失败比直接报错难查得多。
    PROFILE_FIELD_RETIRED(1042, "院系与年级请通过 school-profile 或 onboarding-complete 设置"),

    // 评论不存在。与 POST_NOT_FOUND 分开是因为「帖没了」和「某条回复没了」
    // 对前端的处理不同：前者应退回帖子页，后者应只把该条回复置灰。
    COMMENT_NOT_FOUND(1043, "评论不存在或已删除"),

    /**
     * 学号核验申请不存在，或该用户当前不在「审核中」状态。
     *
     * <p>两种情形共用一码是刻意的：管理员在手机上点「通过」时，
     * 若另一个管理员刚批过，此时回「用户不存在」是错的（人明明在），
     * 回「状态已变更」才对；但对调用方来说两件事的处理方式相同——
     * 刷新列表即可，不需要区分。分开两个码只增加分支，不增加信息量。
     */
    VERIFY_NOT_FOUND(1044, "该学号核验申请不存在或已不在审核中"),

    // 1047 起:校园限时活动集市(mkt)
    MKT_NOT_FOUND(1047, "活动不存在或已下架"),
    MKT_NOT_OWNER(1048, "仅发布者本人可操作该活动"),
    MKT_CATEGORY_INVALID(1049, "一级分类不合法"),
    MKT_TITLE_INVALID(1050, "标题长度需在 15-25 字符之间"),
    MKT_CONTENT_INVALID(1051, "详情需在 1-500 字之间"),
    MKT_EXPIRE_INVALID(1052, "投放截止时间不合法(需为未来且单次不超过 12 小时)"),
    MKT_REACTIVATE_LIMIT(1053, "24 小时内重新激活次数已达上限"),

    // 频率类:复用 HTTP 429 段(枚举内本就混排 4xx 与 1xxx)
    TOO_MANY_REQUESTS(429, "操作过于频繁,请稍后再试");


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
