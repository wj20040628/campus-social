package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 「谁还没看」名单行（F5 公告）。
 *
 * <p>只回昵称与院系年级，不回邮箱手机号 —— 管理员在手机上要能定位到人
 * 去线下找人，但这条名单不构成正当的联系方式批量导出面。
 */
@Data
public class UnreadUserVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    private String avatarUrl;

    private String dept;

    private String grade;
}