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

    /**
     * 1被关注 2被赞 3被评 4私信 5活动 6审核中 7审核通过 8审核未通过
     * 9被@ 10集市留言 11反馈回复 13文件过审 14文件驳回 15文件下架
     * 16认证通过 17认证驳回 18举报结果 19账号封禁 20内容下架 21账号解封
     * 22被邀请加入群聊（targetId=groupId） 23圈子被解散（targetId=circleId）
     */
    private Integer type;

    /** 关联实体 id(帖子/评论/会话/活动/反馈) */
    private Long targetId;

    /** 被指向的帖子 id(点赞/评论跳帖子详情用),其他类型可空 */
    private Long postId;

    /** 触发者 */
    private Long actorId;

    /** 自由文本通知内容(如反馈回复原文);旧类型通知恒为 NULL */
    private String content;

    /** 0未读 1已读 */
    private Integer isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
