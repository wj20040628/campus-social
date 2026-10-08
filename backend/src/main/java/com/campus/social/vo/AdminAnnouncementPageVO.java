package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 后台公告列表页（F5，openapi {@code AdminAnnouncementPageResponse}）。
 *
 * <p>与前台列表页分开的原因：本页每行要多带 {@code totalTargetCount} 与
 * {@code reachRate}，而触达率的分母是「本校应触达人数」——
 * 若每行各查一次，一次分页会被放大成 N 次 count 查询。
 * 服务层先算一次分母再逐行复用。
 */
@Data
public class AdminAnnouncementPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<AnnouncementAdminItemVO> list;

    /** 下一页游标；null 表示没有更多 */
    private Long nextCursor;

    private boolean hasMore;
}