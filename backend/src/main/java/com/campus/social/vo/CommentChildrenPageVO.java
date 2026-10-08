package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 某条评论的直接子回复分页（openapi CommentChildrenPageResponse 的 data）。
 *
 * <p>{@code totalForParent} 是契约要求的字段而非可选：前端要用它算
 * 「共 N 条，已加载 M 条」。若让它改用
 * {@code childrenOverflowCount + 本页长度} 反复相加，
 * 两处相加一旦漏一次就会显示错总数——这正是本字段存在的原因。
 */
@Data
@Builder
public class CommentChildrenPageVO {

    private List<CommentVO> list;

    private Long nextCursor;

    private boolean hasMore;

    /** 该节点的直接子回复总条数，不受本次分页 size 限制 */
    private int totalForParent;
}