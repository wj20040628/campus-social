package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long convId;
    private Long senderId;
    private Long receiverId;

    private String content;

    /** 0未读 1已读(receiver 侧) */
    private Integer status;

    /** 0正常 1已撤回(仅发送方 60s 内可撤) */
    private Integer isRecalled;

    /** 引用回复:被引消息 id(可空) */
    private Long replyToId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
