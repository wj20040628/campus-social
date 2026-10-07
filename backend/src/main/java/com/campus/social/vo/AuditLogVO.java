package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志展示 VO(管理后台·操作日志页)
 * operator* 回填操作人信息(批量查,防 N+1);userId=null 表示系统任务,operatorUsername 显示"系统"
 */
@Data
@Builder
public class AuditLogVO {
    private Long id;
    private Long userId;          // 操作人 id,NULL=系统/定时任务
    private String operatorName;  // 昵称/用户名(系统显示"系统")
    private String operatorAvatar;
    private String action;        // login/create_post/review_approve/...
    private String targetType;
    private Long targetId;
    private String detail;        // 自由文本/JSON,前端原样展示不解析
    private String ip;
    private LocalDateTime createTime;
}
