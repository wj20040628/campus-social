package com.campus.social.vo;

import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告列表项（F5，前台与后台共用）。
 *
 * <p>{@code summary} 只截正文前 80 字：整篇下发会让移动端首屏变慢，
 * 而列表页本来也只展示开头几行。
 *
 * <p>{@code isRead} 是「相对当前用户」的，故列表里每一行的这个值都可能不同，
 * 也是为什么后台列表不能复用不带 read 状态的通用查询结果缓存。
 *
 * <p>用 {@code @SuperBuilder}：后台的 {@link AnnouncementAdminItemVO} 继承本类，
 * 普通 {@code @Builder} 看不到父类字段，会静默生成一堆 null。
 */
@Data
@SuperBuilder
public class AnnouncementListItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /** 正文前 80 字 */
    private String summary;

    /** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
    private Integer category;

    /** 0否 1是 */
    private Integer isTop;

    private LocalDateTime topExpireTime;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    private LocalDateTime publishTime;

    private Integer readCount;

    /** 当前用户是否已读 */
    private Boolean isRead;

    private AnnouncementPublisherVO publisher;
}