package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 推荐流分页结果。
 *
 * <p>游标是复合的 {@code (score, id)}:排序先看共同标签数,同分再按帖子 id 倒序。
 * 只回传 id 会在同分处跳页或重复,所以下一页必须把上一页末条的
 * {@code nextScore} 与 {@code nextId} 一起带回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendFeedVO {

    private List<PostVO> list;

    private boolean hasMore;

    /** 下一页游标:上一页末条的共同标签数;hasMore=false 时为 null */
    private Integer nextScore;

    /** 下一页游标:上一页末条的帖子 id;hasMore=false 时为 null */
    private Long nextId;
}
