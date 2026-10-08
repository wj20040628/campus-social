package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端举报列表行（F4）。
 *
 * <p>字段与 {@code report} 表一一对应，另加联表带出的 {@code reporterNickname}。
 * 手机端审核页要在一屏内完成判断，所以必须直接带上举报人昵称与被举报内容摘要，
 * 不能让前端再发一轮请求。
 */
@Data
public class ReportListVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long reporterId;

    /** 举报人昵称，联表带出；用户被注销时为 null，前端需降级展示为「已注销用户」 */
    private String reporterNickname;

    /** 1用户 2帖子 3评论 4聊天消息 5搭子 */
    private Integer targetType;

    private Long targetId;

    /** 被举报对象的正文摘要，由 service 按 targetType 分派查询后填入 */
    private String targetSummary;

    /** 1骚扰 2广告 3色情 4诈骗 5其他 */
    private Integer reasonType;

    private String reasonText;

    /** 0待审 1成立 2驳回 */
    private Integer status;

    private Long handlerId;

    private LocalDateTime handleTime;

    private String handleNote;

    private LocalDateTime createTime;
}