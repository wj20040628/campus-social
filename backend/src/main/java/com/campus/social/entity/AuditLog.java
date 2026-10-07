package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志(只增不删)
 */
@Data
@TableName("audit_log")
public class AuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者,NULL=系统/定时任务 */
    private Long userId;

    /** 动作:login/register/create_post/delete_post/signup/blacklist/review_approve/review_reject */
    private String action;

    /** 操作对象类型:post/relation/activity/user */
    private String targetType;

    private Long targetId;

    /** JSON 或其他详情 */
    private String detail;

    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
