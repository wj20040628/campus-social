package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报(F4,上线门槛项)。
 *
 * <p><b>为什么举报不塞进 review_task</b>：{@code review_task} 是帖子专用队列
 * （{@code post_id BIGINT NOT NULL} + {@code uk_post}），而本表的
 * {@code target_type} 有 5 种取值（1用户 2帖子 3评论 4聊天消息 5搭子），
 * 多数不是帖子。硬塞需要把 review_task 改造成多态，侵入一条已跑通的审核链路。
 * 故在 report 表内闭环，{@code status} 本身就是 AC-14 要求的「处理状态回执」。
 */
@Data
@TableName("report")
public class Report implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reporterId;

    /** 1用户 2帖子 3评论 4聊天消息 5搭子 */
    private Integer targetType;

    private Long targetId;

    /** 1骚扰 2广告 3色情 4诈骗 5其他 */
    private Integer reasonType;

    /** 补充说明 */
    private String reasonText;

    /** 举报提交时的目标快照(JSON: 标题/正文/作者/媒体/时间);历史举报为 null */
    private String targetSnapshot;

    /** 0待审 1成立 2驳回 */
    private Integer status;

    private Long handlerId;

    private LocalDateTime handleTime;

    private String handleNote;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
