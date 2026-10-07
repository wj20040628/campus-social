package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.io.Serializable;

@Data
@TableName("user")
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮箱或手机号 */
    private String username;

    /** BCrypt */
    private String passwordHash;

    /** 学号(可选),UNIQUE */
    private String studentNo;

    private String nickname;
    private String avatarUrl;
    private String grade;
    private String dept;

    /** 0正常 1冻结 2注销 */
    private Integer status;

    /** 角色:0普通用户 1管理员 */
    private Integer role;

    private Integer followerCount;
    private Integer followingCount;
    private Integer unreadNotifyCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
