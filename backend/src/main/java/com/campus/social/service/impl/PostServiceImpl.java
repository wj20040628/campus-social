package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;

import com.campus.social.dto.CreateCommentDTO;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.PostService;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.CommentChildrenPageVO;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static com.campus.social.common.ResultCode.*;

/**
 * 帖子/评论/点赞/Feed 实现
 *
 * 写扩散核心:
 * create() 事务1:post 入库 + 敏感词判定 + review_task(如需审核)
 * 之后(异步,本简化用同步):
 * - 查 follower → 批量 INSERT feed_item(500 条/批,事务2)
 * - 发 notify(type=2 被赞/3 被评 在点赞/评论时发)
 *
 * MVP 用同步扩散(校园粉丝 <5000);后续可换 RabbitMQ/RocketMQ
 * 死信兜底:扩散失败不影响帖子本身,feed_item 缺失可由 Feed 页降级查 post 表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final int BATCH_SIZE = 500;
    private static final int TARGET_TYPE_POST = 1;
    private static final int NOTIFY_TYPE_LIKE = 2;
    private static final int NOTIFY_TYPE_REVIEWING = 6; // 内容待审(作者)
    private static final int NOTIFY_TYPE_APPROVED = 7; // 审核通过(作者)
    private static final int NOTIFY_TYPE_REJECTED = 8; // 审核驳回(作者)

    private final PostMapper postMapper;
    private final FeedItemMapper feedItemMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final UserMapper userMapper;
    private final NotifyMapper notifyMapper;
    private final PostNotifySupport notifySupport;
    private final com.campus.social.service.AuditService auditService;
    private final com.campus.social.mapper.PostTopicMapper postTopicMapper;
    /** 评论域已拆出,帖子侧只做委托 */
    private final CommentServiceImpl commentService;
    /** Feed 查询域已拆出,帖子侧只做委托 */
    private final PostFeedQueryService feedQuery;
    /** 写路径(发帖/编辑/审核联动)已拆出 */
    private final PostWriteService writeService;
    /** 写扩散,发帖与审核通过共用 */
    private final FeedFanoutService fanout;
    /** 圈内封禁拦截:点赞前查 ban_status */
    private final com.campus.social.service.CircleAdminService circleAdminService;

    // ---------------------------------------------------------------- 发动态

    @Override
    @Transactional
    public long create(long authorId, CreatePostDTO dto) {
        return writeService.create(authorId, dto);
    }

    // ---------------------------------------------------------------- 编辑

    @Override
    @Transactional
    public PostVO edit(long postId, CreatePostDTO dto, long authorId) {
        // write 层永远返回 null（成功落库或无变更都一样），所以这里必然回详情。
        // 保留 != null 判断是为了让该分支在 write 层签名变化时不会静默出错——
        // 若将来 write 层改为真的回传 VO，这个三元会自然生效而不是被忽略。
        // 失败路径（非作者 / 帖子不存在 / 已删除 / 内容为空 / 拒发敏感词）
        // 由 write 层抛 BusinessException，不会走到这里
        PostVO changed = writeService.edit(postId, dto, authorId);
        return changed != null ? changed : detail(postId, authorId);
    }

    @Override
    public PostVO detail(long postId, long viewerId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        // 下架/删除对非作者不可见
        if ((post.getStatus() == 2 || post.getStatus() == 3) && !post.getAuthorId().equals(viewerId)) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        // 浏览量原子 +1(每次进详情算一次)
        postMapper.adjustViewCount(postId);
        // 转换与回填交给 Feed 查询域，保证详情页与列表页的作者/话题展示规则完全一致
        PostVO vo = feedQuery.toDetailVo(post, viewerId);
        // viewCount 用 +1 后的值(刚原子加了 1)
        vo.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        return vo;
    }

    @Override
    @Transactional
    public void delete(long postId, long authorId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        if (!post.getAuthorId().equals(authorId)) {
            throw new BusinessException(NOT_POST_AUTHOR, "非帖子作者,无法删除");
        }

        // 级联物理删除(先删引用方,最后删主表)
        // 1. 评论点赞(该帖所有评论的 like)
        LambdaQueryWrapper<Comment> cqw = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId);
        for (Comment c : commentMapper.selectList(cqw)) {
            likeMapper.hardDeleteByComment(c.getId());
        }
        // 2. 评论本身
        commentMapper.hardDeleteByPost(postId);
        // 3. 帖子点赞
        likeMapper.hardDeleteByPost(postId);
        // 4. feed 收件箱扩散项
        feedItemMapper.hardDeleteByPost(postId);
        // 4.5 帖子-话题关联(并回退话题 post_count)
        postTopicMapper.hardDeleteByPost(postId);
        // 5. 指向该帖/评论的通知
        notifyMapper.hardDeleteByTarget(postId);
        // 6. 主表
        postMapper.hardDelete(postId);

        log.info("post hard-deleted: id={}, author={}", postId, authorId);
        // 审计:删帖
        auditService.record(authorId, "delete_post", "post", postId, "物理删除+级联", null);
    }

    // ---------------------------------------------------------------- Feed 列表

    @Override
    public CursorPage<PostVO> feed(long viewerId, Long lastId, int size, String by, String order, String topic) {
        return feedQuery.feed(viewerId, lastId, size, by, order, topic);
    }

    @Override
    public CursorPage<PostVO> feedByTopic(String topicName, Long lastId, int size, long viewerId) {
        return feedQuery.feedByTopic(topicName, lastId, size, viewerId);
    }

    @Override
    public CursorPage<PostVO> feedFollowing(long viewerId, Long lastId, int size) {
        return feedQuery.feedFollowing(viewerId, lastId, size);
    }

    @Override
    public CursorPage<PostVO> hot(int size, Long lastHotScore, long viewerId) {
        return feedQuery.hot(size, lastHotScore, viewerId);
    }

    // ---------------------------------------------------------------- 审核通过扩散

    @Override
    @Transactional
    public void fanoutOnApproved(long postId) {
        // 写扩散在 FeedFanoutService：发帖直接通过与审核后补扩散共用同一份逻辑
        Post p = postMapper.selectById(postId);
        if (p != null) {
            fanout.fanout(p.getAuthorId(), postId);
        }
    }

    // ---------------------------------------------------------------- 点赞

    @Override
    @Transactional
    public void like(long postId, long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        // 圈内封禁拦截:帖子属某圈且点赞者被圈内封禁 → 拒(圈主/圈管理恒不拦,见 isBanned)
        if (post.getCircleId() != null && circleAdminService.isBanned(post.getCircleId(), userId)) {
            throw new BusinessException(CIRCLE_MEMBER_BANNED, "你已被圈主/圈管理封禁,无法在此圈子操作");
        }
        // 幂等:已赞不重复
        LambdaQueryWrapper<Like> qw = new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, TARGET_TYPE_POST)
                .eq(Like::getTargetId, postId);
        Long cnt = likeMapper.selectCount(qw);
        if (cnt > 0) {
            log.debug("like idempotent skip: {} -> {}", userId, postId);
            return;
        }
        Like like = new Like();
        like.setUserId(userId);
        like.setTargetType(TARGET_TYPE_POST);
        like.setTargetId(postId);
        likeMapper.insert(like);
        // 同事务 ±1
        postMapper.adjustLikeCount(postId, 1);
        // 通知作者(非自己)
        if (!post.getAuthorId().equals(userId)) {
            notifySupport.pushNotify(post.getAuthorId(), NOTIFY_TYPE_LIKE, postId, userId, postId);
        }
        // 热度分:简化即时 +1(HotScoreTask 定期重算更精细)
        postMapper.updateHotScore(postId, post.getHotScore() + 1.0);
    }

    @Override
    @Transactional
    public void unlike(long postId, long userId) {
        // 物理删除(绕过 @TableLogic 软删,否则残留行锁住 uk_like,再点赞会撞 Duplicate entry)
        int deleted = likeMapper.hardDelete(userId, TARGET_TYPE_POST, postId);
        if (deleted == 0) {
            return; // 未点过,幂等
        }
        postMapper.adjustLikeCount(postId, -1);
        Post post = postMapper.selectById(postId);
        if (post != null) {
            postMapper.updateHotScore(postId, Math.max(0, post.getHotScore() - 1.0));
        }
    }

    // ---------------------------------------------------------------- 评论

    // 事务标在委托方法上：Spring 事务基于代理，同类内部调用不生效。
    // 评论的真实事务边界在 CommentServiceImpl#comment（跨类调用，代理正常生效），
    // 此处的 @Transactional 只是保证从 PostService 入口进来时也处于事务中
    @Override
    @Transactional
    public long comment(long postId, CreateCommentDTO dto, long userId) {
        // 委托给评论域。三级层级规则、@提及解析、按层级分派通知目标都在那边
        return commentService.comment(postId, dto, userId);
    }

    @Override
    public CursorPage<CommentVO> comments(long postId, Long lastId, int size, long viewerId) {
        return commentService.comments(postId, lastId, size, viewerId);
    }

    /**
     * 评论子回复溢出补齐（read 侧，真实逻辑在 CommentServiceImpl）。
     *
     * <p>同样跨类调用而非本地转调：与 comment/comments 保持一致的处理链路，
     * 避免「同一件事在两个类里各有一份实现」这种最难的漂移来源。
     */
    @Override
    public CommentChildrenPageVO commentChildren(long postId, long commentId,
            Long lastId, int size, long viewerId) {
        return commentService.commentChildren(postId, commentId, lastId, size, viewerId);
    }

    @Override
    public CursorPage<PostVO> myPosts(long authorId, long viewerId, Long lastId, int size) {
        return feedQuery.myPosts(authorId, viewerId, lastId, size);
    }

    // ---------------------------------------------------------------- 私有


}
