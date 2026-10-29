package com.campus.social.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发动态入参(新建/编辑共用)
 * media: JSON 字符串,形如 [{"type":"img","url":"/uploads/xxx"},{"type":"video","url":"...","dur":12}]
 * circleId: 可选,挂到某圈子
 * topics: 可选,话题名列表(如 ["校园生活","美食"]),存 topic + post_topic
 *
 * 新建:content 必填;编辑:content 可空(允许只改话题/媒体,保留原内容)。
 * 用 DTO 层校验而非注解 @NotBlank:编辑场景下前端可能只传 topics,
 * 后端 service 层对"全空"做兜底拒绝,语义比 @NotBlank 更准确。
 *
 * anonymous(树洞):仅创建时生效,编辑忽略——匿名身份一次定终身。
 */
@Data
public class CreatePostDTO {

    @Size(max = 5000)
    private String content;

    /** JSON 字符串,后端用 Jackson 解析校验 */
    private String media;

    private Long circleId;

    /** 话题名列表(不带 #) */
    private List<String> topics;

    /**
     * 树洞匿名帖标记。
     *
     * <p>2026-10-27 起匿名发布下线:传 {@code true} 会被后端拒绝,
     * 避免旧客户端静默把内容实名发出。字段保留只为兼容旧客户端请求体。
     */
    private Boolean anonymous;
}
