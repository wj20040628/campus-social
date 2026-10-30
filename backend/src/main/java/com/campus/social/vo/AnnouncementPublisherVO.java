package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 公告发布人（F5）。
 *
 * <p>2026-10-30 起不再下发 {@code verified} / {@code schoolName}：
 * 校园信息只是个人资料，不能被当作公开发布方的身份背书。
 * 公告权限由管理员角色决定，不由校园信息决定。
 */
@Data
@Builder
public class AnnouncementPublisherVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    private String avatarUrl;
}