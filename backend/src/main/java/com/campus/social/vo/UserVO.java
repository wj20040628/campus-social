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
    /**
     * 院系 / 年级字典 id，与上面的名称快照并存。
     *
     * <p>为什么 VO 里 id 和名称都要有：名称供展示，id 供前端判断「这个值是不是
     * 字典里的正式项」。字典化之前注册的用户 id 为 NULL 但名称有值，
     * 前端若用 {@code deptId === null} 判定「用户没填院系」就会把这些人
     * 错误地推进引导页。正确判据是 id 与名称同时为空。
     */
    private Long deptId;
    private Long gradeId;
    private Integer followerCount;
    private Integer followingCount;
    private Integer unreadNotifyCount;
    /** 0普通用户 1管理员 */
    private Integer role;
    private LocalDateTime createTime;
}
