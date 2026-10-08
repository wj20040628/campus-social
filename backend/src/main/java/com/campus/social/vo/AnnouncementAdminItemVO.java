package com.campus.social.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * 后台公告列表行（F5，openapi {@code AdminAnnouncementListItem}）。
 *
 * <p>继承前台的 {@link AnnouncementListItemVO}：两者的标题/分类/置顶/已读数
 * 字段完全一致，重复声明一遍只会在改契约时漏改一处。
 * 只追加后台独有的触达统计。
 *
 * <p>用 {@code @SuperBuilder} 而非 {@code @Builder}：父类字段不在子类的
 * {@code @Builder} 可见范围里，用普通 builder 会静默丢掉 id、title 等字段——
 * 编译能过、运行时全是null，是最难查的一类错误。
 */
@Data
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class AnnouncementAdminItemVO extends AnnouncementListItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 本校应触达人数，用于算触达率 */
    private Integer totalTargetCount;

    /** readCount / totalTargetCount，0 到 1 */
    private Double reachRate;
}