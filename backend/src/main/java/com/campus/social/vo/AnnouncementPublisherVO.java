package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 公告发布人（F5）。
 *
 * <p>{@code schoolName} 只在已认证时返回（AC-12）：
 * 未认证时挂一个学校名是伪造权威，校方账号必须走认证才有这个标识。
 */
@Data
@Builder
public class AnnouncementPublisherVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    private String avatarUrl;

    /** 是否已通过校内认证（AC-12） */
    private Boolean verified;

    /** 已认证时返回学校名；未认证为 null */
    private String schoolName;
}