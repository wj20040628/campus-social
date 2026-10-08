package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 认证状态（F2）。
 *
 * <p>{@code canStrangerChat} 与 {@code canPublishAnnouncement} 两个布尔量
 * 是 AC-11 的对外表达。前端不该自己从 verifyStatus 推这两个能力：
 * 那是「业务规则在前端重写一遍」，规则一变（如以后加「认证满 7 天才能发公告」）
 * 前端就会静默出错。
 */
@Data
@Builder
public class VerifyStatusVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 0未认证 1已认证 2审核中 3驳回 */
    private Integer verifyStatus;

    private String verifyStatusText;

    private Long schoolId;

    /** 已认证时返回学校名；未认证为 null */
    private String schoolName;

    /** 认证通过时间；未认证为 null */
    private java.time.LocalDateTime verifiedAt;

    /** verifyStatus=3 时的驳回理由 */
    private String rejectReason;

    /** 是否可发起搭话（AC-11） */
    private Boolean canStrangerChat;

    /** 是否可发布公告（AC-11） */
    private Boolean canPublishAnnouncement;

    /** 引导第 1 步是否完成（dept 与 grade 均已填） */
    private Boolean profileComplete;
}