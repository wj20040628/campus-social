package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 群成员(退群物理删,软删只留痕迹——沿用关系表"物理删"约定) */
@Data
@TableName("group_member")
public class GroupMember implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;

    /** 1群主 2成员 */
    private Integer role;

    /** 是否被群主禁言 0否 1是(群主恒 0) */
    private Integer muted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
