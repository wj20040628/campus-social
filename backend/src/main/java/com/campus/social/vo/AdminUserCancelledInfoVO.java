package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 已注销账号的留存与清理信息（2026-11-08）。 */
@Data
public class AdminUserCancelledInfoVO {
    private LocalDateTime cancelledAt;
    private long retentionDays;
    private LocalDateTime purgeDueAt;
    private LocalDateTime purgedAt;
    /** 面向管理员的一句话说明，避免各自猜口径 */
    private String policy;
}
