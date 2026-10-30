package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告查询的行投影（mapper 层内部用）。
 *
 * <p><b>为什么不用 {@code Map<String,Object>}</b>：Map 的键是字符串，
 * 改列名（如 {@code publisher_nickname} → {@code publisherNickname}）
 * 编译期无感知、运行期静默返回 null。行投影是有类型的，
 * 改列名会导致 MyBatis 映射失败并在启动或首次查询时暴露。
 *
 * <p>发布人字段摊平成 {@code publisher*} 前缀，由 service 组装成嵌套的
 * {@link AnnouncementPublisherVO}。不直接在行投影里放嵌套对象——
 * 那需要 resultMap 关联配置，纯注解 SQL 写不了。
 */
@Data
public class AnnouncementRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private String content;

    /** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
    private Integer category;

    private Long publisherId;

    private Long schoolId;

    /** 0否 1是 */
    private Integer isTop;

    private LocalDateTime topExpireTime;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    private LocalDateTime publishTime;

    private Integer readCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 联表带出的发布人信息（LEFT JOIN user） ----

    private String publisherNickname;

    private String publisherAvatar;
}