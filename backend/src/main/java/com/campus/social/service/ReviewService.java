package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.MyReviewVO;
import com.campus.social.vo.ReviewTaskVO;

/**
 * 内容审核:review_task 队列
 * - 敏感词 level1 命中 → post.status=0 + review_task 入队
 * - 后台通过:post.status=1 + 写扩散(此时才进 Feed)+ 通知作者
 * - 后台驳回:post.status=2 + 通知作者
 */
public interface ReviewService {

    /** 待审列表(状态 0),游标分页 */
    CursorPage<ReviewTaskVO> pending(long reviewerId, Long lastId, int size);

    /** 已通过/已驳回列表 */
    CursorPage<ReviewTaskVO> processed(long reviewerId, Long lastId, int size);

    /** 审核通过:post.status 0→1,写扩散,通知作者 */
    void approve(long taskId, long reviewerId);

    /** 审核驳回:post.status 0→2,通知作者 */
    void reject(long taskId, long reviewerId, String reason);

    /**
     * 作者侧:我自己的帖子审核结果(被驳/通过 + 理由 + 命中词 + AI 预判)。
     * 让作者知道"为什么被驳、能怎么改",闭环到可申诉/重提。
     */
    CursorPage<MyReviewVO> myReviews(long authorId, Long lastId, int size);
}
