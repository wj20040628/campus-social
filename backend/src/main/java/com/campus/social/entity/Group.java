package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 群(N 人会话)。群成员关系落 group_member,群消息落 group_message,群已读水位落 group_read。
 * 注意:group 是 MySQL 保留字,表名需带反引号,MyBatis-Plus 生成的 SQL 才能正常执行。
 */
@Data
@TableName("`group`")
public class Group implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String avatarUrl;

    /** 群主(user.id) */
    private Long ownerId;

    /** 成员数(建群/拉人时原子累加) */
    private Integer memberCount;

    private Long lastMsgId;

    /** 1正常 0解散 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
