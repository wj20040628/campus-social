package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户卡片(脱敏,不暴露 passwordHash / studentNo 等敏感字段)
 */
@Data
@Builder
public class UserVO {
    private Long id;
    private String username;     // 邮箱或手机号(部分脱敏:显示前几位+后几位)
    private String nickname;
    private String avatarUrl;
    private String grade;
    private String dept;
    private Integer followerCount;
    private Integer followingCount;
    private Integer unreadNotifyCount;
    /** 0普通用户 1管理员 */
    private Integer role;
    private LocalDateTime createTime;
}
