package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 推荐圈子项（openapi RecommendCircleItem）。
 *
 * <p><b>关于 description 与 avatarUrl 恒为 null</b>：
 * {@code circle} 表只有 {@code id / name / owner_id / member_count / status /
 * create_time / update_time / is_deleted}，没有简介列也没有头像列
 * （实测 {@code SHOW COLUMNS FROM circle}）。契约把这两项定义为 nullable，
 * 故此处保留字段但不下发数据——写死一句通用简介冒充真实简介，
 * 比返回 null 更糟：用户点进圈子发现对不上，信任就没了。
 * 待圈子支持编辑简介后，本 VO 无需改动即可自动生效。
 *
 * <p><b>关于 commonTagCount 恒为 0</b>：库里没有圈子与兴趣标签的关联表，
 * 「圈子标签」这个概念目前不存在，无从计算重合度。
 * ARCHITECTURE §3 Q10.4 给的参考 SQL 同样只按 {@code member_count} 与
 * {@code create_time} 排序，未引入标签维度——本实现与之一致。
 */
@Data
@Builder
public class RecommendCircleVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** circle 表暂无简介列，恒为 null（见类注释） */
    private String description;

    /** circle 表暂无头像列，恒为 null（见类注释） */
    private String avatarUrl;

    private Integer memberCount;

    /** 无圈子-标签关联表，恒为 0（见类注释） */
    private Integer commonTagCount;

    /** 是否已加入。推荐列表已排除已加入的圈子，正常情况恒为 false */
    private Boolean joined;
}
