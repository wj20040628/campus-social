package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 群消息(N 人,无 receiver 单值;已读走 group_read 水位表) */
@Data
@TableName("group_message")
public class GroupMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long senderId;

    private String content;

    /** 0未读 1已读(发送方视角汇总,群内至少一人读到即 1) */
    private Integer status;

    /** 0正常 1已撤回(仅发送方 8s 内可撤) */
    private Integer isRecalled;

    /** 引用回复:被引群消息 id(可空) */
    private Long replyToId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
