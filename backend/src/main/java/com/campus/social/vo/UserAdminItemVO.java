package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户处置列表行（AdminUserController，openapi schema 同名）。
 *
 * <p>username 下发**打码版**（UserVoAssembler.maskUsername）：
 * 管理员定位用户用昵称/学号就够了，完整学号是敏感凭证，
 * 但本产品学号本就是管理员核验的对象，故 studentNo 下发明文；
 * username（登录标识）与密码同源体系，维持打码口径（与审计日志页一致）。
 */
@Data
public class UserAdminItemVO {

    private Long userId;

    private String nickname;

    /** 登录标识（打码版，同审计日志 operatorName 的口径） */
    private String username;

    private String avatarUrl;

    /** 学号；未提交认证的用户为 null */
    private String studentNo;

    private String dept;

    private String grade;

    /** 0 未认证 / 2 审核中 / 1 已认证 / 3 已驳回 */
    private Integer verifyStatus;

    /** 1 = 管理员（前端据此隐藏「封禁」按钮） */
    private Integer role;

    /** 0 正常 / 1 冻结（封禁中） */
    private Integer status;

    /** 解封时间；永久封禁为 null，未封禁为 null——前端配 status 一起判 */
    private LocalDateTime bannedUntil;

    private LocalDateTime createTime;
}
