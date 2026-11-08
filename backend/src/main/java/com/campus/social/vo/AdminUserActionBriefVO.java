package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户详情里的单条账号处置记录（2026-11-08）。 */
@Data
public class AdminUserActionBriefVO {
    private String action;
    private String detail;
    private LocalDateTime createTime;
    /** 操作人昵称；系统/定时任务为「系统」 */
    private String operator;
}
