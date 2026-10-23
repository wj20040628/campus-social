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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final PostFavoriteMapper favoriteMapper;
    private final UserMapper userMapper;
    private final NotifyMapper notifyMapper;
    private final PostNotifySupport notifySupport;
    private final com.campus.social.service.AuditService auditService;
    private final com.campus.social.mapper.PostTopicMapper postTopicMapper;
    /** 评论域已拆出,帖子侧只做委托 */
    private final CommentServiceImpl commentService;
    /** Feed 查询域已拆出,帖子侧只做委托 */
    private final PostFeedQueryService feedQuery;

    /** 装配器:收藏/赞过列表要按记录顺序回填 VO,与 Feed 共用同一套展示规则 */
    private final PostVoAssembler assembler;
    /** 写路径(发帖/编辑/审核联动)已拆出 */
    private final PostWriteService writeService;
    /** 写扩散,发帖与审核通过共用 */
    private final FeedFanoutService fanout;
    /** 圈内封禁拦截:点赞前查 ban_status */
    private final com.campus.social.service.CircleAdminService circleAdminService;
    /** 首页首屏缓存:删帖后必须让它失效,否则被删内容还会在首页挂最多 60 秒 */
    private final FeedPageCacheService feedCache;

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
        // 3.5 帖子收藏(不清会让「我的收藏」里留下打不开的幽灵条目)
        favoriteMapper.hardDeleteByPost(postId);
        // 4. feed 收件箱扩散项
        feedItemMapper.hardDeleteByPost(postId);
        // 4.5 帖子-话题关联(并回退话题 post_count)
        postTopicMapper.hardDeleteByPost(postId);
        // 5. 指向该帖的所有通知(按 post_id 删,审查 P0-9:旧按 target_id 删会误删别的帖子评论的通知)
        notifyMapper.hardDeleteByPostId(postId);
        // 6. 主表
        postMapper.hardDelete(postId);

        log.info("post hard-deleted: id={}, author={}", postId, authorId);
        // 首页缓存失效(事务提交后执行)。不失效的话,帖子里已删的图片和正文
        // 还会在首页卡片上继续展示最多 60 秒——内容是删了,但用户还能看见。
        feedCache.invalidate();
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
        try {
            likeMapper.insert(like);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            /*
             * 并发兜底(2026-10-23 修,审查 P1-20):上面的 selectCount 检查不是原子的,
             * 移动端双击/两个标签页同时点赞时两个请求都能通过判重,第二个 insert 撞 uk_like。
             * 旧行为是抛 500,用户看到"点赞失败"但实际已赞成功 —— 语义错误。
             * 这里把 DuplicateKey 当作"已赞"处理:回滚本事务(计数尚未加,不会重复),
             * 直接返回成功。
             */
            log.debug("like duplicate ignored (already liked): {} -> {}", userId, postId);
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return;
        }
        // 同事务 ±1
        postMapper.adjustLikeCount(postId, 1);
        // 通知作者(非自己)
        if (!post.getAuthorId().equals(userId)) {
            notifySupport.pushNotify(post.getAuthorId(), NOTIFY_TYPE_LIKE, postId, userId, postId);
        }
        // 热度分:简化即时 +1(HotScoreTask 定期重算更精细)
        postMapper.adjustHotScore(postId, 1.0); // 增量自增(审查 P1-15)
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
            postMapper.adjustHotScore(postId, -1.0); // 增量自减,GREATEST(0,...) 防负(审查 P1-15)
        }
    }

    // ---------------------------------------------------------------- 收藏

    @Override
    @Transactional
    public void favorite(long postId, long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        // 圈内封禁与点赞同口径:被封禁的人在圈内帖子上不能做任何互动
        if (post.getCircleId() != null && circleAdminService.isBanned(post.getCircleId(), userId)) {
            throw new BusinessException(CIRCLE_MEMBER_BANNED, "你已被圈主/圈管理封禁,无法在此圈子操作");
        }
        // 幂等:已收藏直接返回。唯一键 uk_fav_user_post 是最后的兜底,
        // 真正的并发窗口靠数据库约束拦,不靠这次 selectCount
        Long cnt = favoriteMapper.selectCount(new LambdaQueryWrapper<PostFavorite>()
                .eq(PostFavorite::getUserId, userId)
                .eq(PostFavorite::getPostId, postId));
        if (cnt != null && cnt > 0) {
            log.debug("favorite idempotent skip: {} -> {}", userId, postId);
            return;
        }
        PostFavorite fav = new PostFavorite();
        fav.setUserId(userId);
        fav.setPostId(postId);
        try {
            favoriteMapper.insert(fav);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发兜底(审查 P1-20,同 like):双击/多标签同时收藏,第二个撞 uk_fav_user_post。
            // 当作"已收藏"返回成功,不回 500。
            log.debug("favorite duplicate ignored: {} -> {}", userId, postId);
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return;
        }
        // 计数与明细同事务:要么一起成功,要么一起回滚
        postMapper.adjustFavoriteCount(postId, 1);
        // 刻意不发通知 —— 见 PostService#favorite 注释
    }

    @Override
    @Transactional
    public void unfavorite(long postId, long userId) {
        // 物理删除,理由同 unlike:软删残留行会锁住 uk_fav_user_post,
        // 再收藏时 INSERT 撞 Duplicate entry
        int deleted = favoriteMapper.hardDelete(userId, postId);
        if (deleted == 0) {
            return; // 没收藏过,幂等
        }
        postMapper.adjustFavoriteCount(postId, -1);
    }

    @Override
    public CursorPage<PostVO> favoritePosts(long userId, long viewerId, Long lastId, int size) {
        List<PostFavorite> rows = favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>()
                .eq(PostFavorite::getUserId, userId)
                .lt(lastId != null, PostFavorite::getId, lastId)
                .orderByDesc(PostFavorite::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<PostFavorite> page = rows.stream().limit(size).toList();
        Long next = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        List<Long> postIds = page.stream().map(PostFavorite::getPostId).toList();
        return new CursorPage<>(toVoInRecordOrder(postIds, viewerId), next, hasMore);
    }

    @Override
    public CursorPage<PostVO> likedPosts(long userId, long viewerId, Long lastId, int size) {
        List<Like> rows = likeMapper.selectList(new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, TARGET_TYPE_POST)
                .lt(lastId != null, Like::getId, lastId)
                .orderByDesc(Like::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<Like> page = rows.stream().limit(size).toList();
        Long next = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(toVoInRecordOrder(
                page.stream().map(Like::getTargetId).toList(), viewerId), next, hasMore);
    }

    /**
     * 按「收藏/点赞记录的先后顺序」把帖子 id 还原成 VO 列表。
     *
     * <p><b>为什么不能直接 toVoList(selectBatchIds(ids))</b>:
     * {@code IN (...)} 的返回顺序由数据库决定,与传入顺序无关。
     * 直接拿来用会把「最近收藏在最前」的排序打乱,翻页时内容来回跳。
     *
     * <p>过滤掉非 status=1:作者删帖或圈内下架之后,
     * 收藏夹里不该留下点进去打不开的幽灵条目。
     */
    private List<PostVO> toVoInRecordOrder(List<Long> postIds, long viewerId) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> byId = new HashMap<>();
        for (Post p : postMapper.selectBatchIds(postIds)) {
            byId.put(p.getId(), p);
        }
        List<Post> ordered = new ArrayList<>(postIds.size());
        for (Long id : postIds) {
            Post p = byId.get(id);
            if (p != null && p.getStatus() != null && p.getStatus() == 1) {
                ordered.add(p);
            }
        }
        return assembler.toVoList(ordered, viewerId);
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
