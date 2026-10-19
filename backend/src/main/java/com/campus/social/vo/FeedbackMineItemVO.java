package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 意见反馈-我的列表行（2026-10-19，「我的反馈」页）。
 *
 * <p>用户看自己提交的反馈,字段是 {@link FeedbackAdminItemVO} 的子集:
 * 不带 {@code userId}/{@code userNickname}/{@code handlerId} —— 这些是
 * 审核侧才要的,透传给提交者既没用又暴露了处理人 id。
 *
 * <p>mediaJson 原样透传字符串,由前端解析成缩略图(与审核列表同形)。
 */
@Data
public class FeedbackMineItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 1功能建议 2使用问题 3内容问题 4界面体验 5其他 */
    private Integer category;

    private String content;

    /** {@code [{"type":"img","url":"..."}]}，空表示没传媒体 */
    private String mediaJson;

    /** 0待处理 1已采纳 2已忽略 */
    private Integer status;

    /** 管理员处置说明,即回复内容;未处置为 null */
    private String handleNote;

    private LocalDateTime handleTime;

    private LocalDateTime createTime;
}
