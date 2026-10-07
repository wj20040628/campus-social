package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;
    private Long userId;

    /** 父评论 id,NULL 表示一级 */
    private Long parentId;

    /** 1帖 2评 3评中评,≤3 */
    private Integer level;

    /** 被回复的人 */
    private Long replyToUserId;

    private String content;

    /** 1正常 0删除 */
    private Integer status;

    /** 被 @ 的用户:昵称 → id(JSON,前端渲染 @昵称 时据此跳对应主页) */
    private String mentioned;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
