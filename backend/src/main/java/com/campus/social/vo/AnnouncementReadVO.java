package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 已读回执（F5，AC-09 幂等）。
 *
 * <p>{@code duplicated=true} 表示此前已读过，本次未新增记录、
 * 也未重复累加 {@code read_count}。已读人数重复累加会让触达率虚高，
 * 而触达率正是管理员判断公告效果的主要依据。
 */
@Data
@Builder
public class AnnouncementReadVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long announcementId;

    private LocalDateTime readTime;

    /** true 表示此前已读过，本次未新增记录 */
    private Boolean duplicated;
}