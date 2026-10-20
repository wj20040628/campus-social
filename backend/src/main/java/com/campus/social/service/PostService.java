package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.dto.CreateCommentDTO;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.vo.CommentChildrenPageVO;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.PostVO;

/**
 * 帖子 + 评论 + 点赞 + Feed(写扩散)
 */
public interface PostService {

    /**
     * 发动态:
     * 1. 敏感词 DFA:level 2 拒发 / level 1 替换后入库
     * 2. 命中入队 → post.status=0 + review_task
     * 3. 通过 → post.status=1
     * 4. 事务1:post 入库 → 发 MQ post.created(由 FeedFanoutService 消费)
     * 5. 写扩散:按 follower 批量 INSERT feed_item(事务2,500 条/批)
     * 6. 生成 notify(被关注的粉丝被通知有新内容)
     */
    long create(long authorId, CreatePostDTO dto);

    /**
     * 编辑帖子(仅作者):
     * - content/media/topics 三者任一变化即持久化
     * - content 若命中 level-1 → 回退 status=0 + 入 review_task(幂等:已 review_task 存在则更新)
     * - 话题变化:删旧的 post_topic,加新的(幂等 upsert),topic.post_count 对旧话题 -1、新话题 +1
     * - 返回编辑后的帖子 VO(带最新 status,前端据此判断是否进入审核流)
     */
    PostVO edit(long postId, CreatePostDTO dto, long authorId);

    PostVO detail(long postId, long viewerId);

    /** 软删除(作者) */
    void delete(long postId, long authorId);

    /** 写扩散 Feed:我关注的。支持按 by(time/like/comment/view)排序 + order(asc/desc)+ 按话题名筛选 */
    CursorPage<PostVO> feed(long viewerId, Long lastId, int size, String by, String order, String topic);

    /** 话题落地页:某话题下所有已通过帖子,按时间倒序 + 游标 */
    CursorPage<PostVO> feedByTopic(String topicName, Long lastId, int size, long viewerId);

    /** 关注的人的帖子(读 feed_item 收件箱,写扩散闭环) */
    CursorPage<PostVO> feedFollowing(long viewerId, Long lastId, int size);

    /** 探索页:按 hot_score 排序(近 24h) */
    CursorPage<PostVO> hot(int size, Long lastHotScore, long viewerId);

    /** 点赞/取消赞(幂等) */
    void like(long postId, long userId);
    void unlike(long postId, long userId);

    /** 评论(非幂等,事务内 ±comment_count + notify) */
    long comment(long postId, CreateCommentDTO dto, long userId);

    CursorPage<CommentVO> comments(long postId, Long lastId, int size, long viewerId);

    /**
     * 某条评论的直接子回复（分页），children 溢出时由前端调它补齐。
     *
     * <p>只查直接子回复、不递归：二级的孩子是三级、三级没有孩子。
     * 存在的理由是 children 有 20 条上限，而不是为了「一次性拿全部评论」。
     */
    CommentChildrenPageVO commentChildren(long postId, long commentId, Long lastId, int size, long viewerId);

    /**
     * 某用户发的帖子。viewerId=authorId(本人)时含待审0/驳回2,
     * 其他人只返回已通过(status=1) —— 他人主页不得带出私有审核内容。
     */
    CursorPage<PostVO> myPosts(long authorId, long viewerId, Long lastId, int size);

    /**
     * 审核通过后写扩散:把已通过(status=1)的帖子投给作者的所有粉丝
     * 由 ReviewService.approve 调用
     */
    void fanoutOnApproved(long postId);
}
