package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 群已读水位:每个成员存"我读到的最后一条 group_message.id"。群未读 = 水位之后、非我发的消息数。 */
@Data
@TableName("group_read")
public class GroupRead implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;

    /** 我读到的最后一条群消息 id(0=还没读过) */
    private Long lastReadMsgId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
