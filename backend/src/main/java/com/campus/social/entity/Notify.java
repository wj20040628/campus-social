package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("notify")
public class Notify implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1被关注 2被赞 3被评 4私信 5活动 */
    private Integer type;

    /** 关联实体 id(帖子/评论/会话/活动) */
    private Long targetId;

    /** 被指向的帖子 id(点赞/评论跳帖子详情用),其他类型可空 */
    private Long postId;

    /** 触发者 */
    private Long actorId;

    /** 0未读 1已读 */
    private Integer isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
