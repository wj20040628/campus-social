package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.Post;
import com.campus.social.entity.Topic;
import com.campus.social.mapper.FeedItemMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.PostTopicMapper;
import com.campus.social.mapper.TopicMapper;
import com.campus.social.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 帖子 Feed 查询域（从 PostServiceImpl 拆出）。
 *
 * <p><b>为什么拆</b>：四个 Feed 入口（综合 / 话题 / 关注 / 热门）与「我的帖子」
 * 共享同一套游标分页语义，而帖子的写路径与之零共用代码。
 * 混在一起时，游标这个最容易出错的细节被埋在写逻辑中间，改分页极易碰坏审核。
 *
 * <p><b>只读，无事务</b>：本类所有方法都是纯查询。
 * VO 装配（作者/话题/点赞态回填）在 {@link PostVoAssembler}，本类只管「查哪些行」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostFeedQueryService {

    private final PostMapper postMapper;
    private final FeedItemMapper feedItemMapper;
    private final TopicMapper topicMapper;
    private final PostTopicMapper postTopicMapper;
    private final PostVoAssembler assembler;

    /**
     * 综合 Feed。
     *
     * <p>by: time(默认) | like | comment | view；order: desc(默认) | asc；topic 可选。
     * 游标用「排序列 + id」做 tie-break，保证同值时翻页稳定不重不漏。
     */
    public CursorPage<PostVO> feed(long viewerId, Long lastId, int size, String by, String order, String topic) {
        boolean asc = "asc".equalsIgnoreCase(order);
        boolean byTime = by == null || "time".equalsIgnoreCase(by);

        // 话题过滤:先查出该话题 id(有就限定,无话题参数则全量)
        Long topicId = null;
        boolean filterByTopic = topic != null && !topic.isBlank();
        if (filterByTopic) {
            Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>()
                    .eq(Topic::getName, topic.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim()));
            topicId = t != null ? t.getId() : -1L; // 查无此话题 → 强制空结果
        }

        // 排序列走白名单,不接受前端直接传列名(拼接进 SQL 的东西必须是服务端定的)
        String sortCol = resolveSortCol(by, byTime);
        String dir = asc ? "ASC" : "DESC";

        List<Post> rows;
        // 快速路径:仅「按时间 + 倒序 + 无话题」走 Lambda(最常见默认场景);
        // 其余都走 SQL 分支,这样 order 才真正生效
        if (byTime && !asc && !filterByTopic) {
            rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, 1)
                    .lt(lastId != null, Post::getId, lastId)
                    .orderByDesc(Post::getId)
                    .last("LIMIT " + (size + 1)));
        } else {
            rows = postMapper.selectFeedBySql(buildFeedSql(sortCol, dir, byTime, asc, topicId, filterByTopic, lastId, size));
        }

        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        Long nextCursor = calcNextCursor(page, hasMore, byTime, by);
        return new CursorPage<>(assembler.toVoList(page, viewerId), nextCursor, hasMore);
    }

    /** 话题落地页:该话题下所有已通过帖子,按时间倒序 + id 游标 */
    public CursorPage<PostVO> feedByTopic(String topicName, Long lastId, int size, long viewerId) {
        String clean = topicName == null ? "" : topicName.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim();
        if (clean.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>().eq(Topic::getName, clean));
        if (t == null) {
            return new CursorPage<>(List.of(), null, false); // 查无此话题
        }
        List<Long> postIds = postTopicMapper.postIdsByTopic(t.getId());
        if (postIds.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        List<Post> candidates = postMapper.selectBatchIds(postIds);
        candidates.sort((x, y) -> y.getId().compareTo(x.getId()));
        if (lastId != null) {
            candidates.removeIf(p -> p.getId() >= lastId);
        }
        candidates = candidates.stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == 1)
                .toList();
        boolean hasMore = candidates.size() > size;
        List<Post> page = candidates.stream().limit(size).toList();
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(assembler.toVoList(page, viewerId), nextCursor, hasMore);
    }

    /** 读我关注的人的帖子(读 feed_item 收件箱,写扩散的读侧) */
    public CursorPage<PostVO> feedFollowing(long viewerId, Long lastId, int size) {
        List<Post> rows = feedItemMapper.selectFeedForFan(viewerId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.subList(0, Math.min(size, rows.size()));
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(assembler.toVoList(page, viewerId), nextCursor, hasMore);
    }

    /** 热门:按 hot_score 排序(MVP 未接 Redis ZSet,直接查库) */
    public CursorPage<PostVO> hot(int size, Long lastHotScore, long viewerId) {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .orderByDesc(Post::getHotScore)
                .last("LIMIT " + size));
        return new CursorPage<>(assembler.toVoList(posts, viewerId), null, false);
    }

    /**
     * 我的帖子：作者看自己全部未删帖子（含 待审0/通过1/驳回2），
     * 卡片按 status 区分标签；排除 is_deleted=1（软删）与 status=3（删除态）。
     */
    public CursorPage<PostVO> myPosts(long authorId, Long lastId, int size) {
        List<Post> rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, authorId)
                .in(Post::getStatus, List.of(0, 1, 2))
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(assembler.toVoList(page, authorId), nextCursor, hasMore);
    }

    /** 单条详情用：转换与回填走装配器，保证与列表页展示规则完全一致 */
    public PostVO toDetailVo(Post post, long viewerId) {
        return assembler.toVo(post, viewerId);
    }

    private String resolveSortCol(String by, boolean byTime) {
        if (byTime) return "p.id";
        if ("like".equalsIgnoreCase(by)) return "p.like_count";
        if ("comment".equalsIgnoreCase(by)) return "p.comment_count";
        if ("view".equalsIgnoreCase(by)) return "p.view_count";
        return "p.id";
    }

    /**
     * 组装 Feed 原生 SQL。
     *
     * <p>所有拼进 SQL 的值都是服务端算出的（sortCol 走白名单、topicId 是查库拿的 Long、
     * 方向是枚举比较结果、lastId 是 Long、size 是 int），前端传入的字符串一个都没进 SQL。
     *
     * <p>已知局限：非 time 排序只用 sortCol 过滤，同值翻页可能重复/漏。
     * 彻底解决需要 (sortCol, id) 复合游标，前端也要回传复合值，放到 MVP 之后。
     */
    private String buildFeedSql(String sortCol, String dir, boolean byTime, boolean asc,
                               Long topicId, boolean filterByTopic, Long lastId, int size) {
        StringBuilder sql = new StringBuilder("SELECT p.* FROM post p ");
        if (filterByTopic) {
            sql.append("JOIN post_topic pt ON pt.post_id = p.id AND pt.topic_id = ")
               .append(topicId)
               .append(" AND pt.is_deleted = 0 ");
        }
        sql.append("WHERE p.status = 1 AND p.is_deleted = 0 ");
        if (lastId != null) {
            sql.append("AND p.").append(sortCol).append(' ').append(asc ? "> " : "< ").append(lastId).append(' ');
        }
        sql.append("ORDER BY ").append(sortCol).append(' ').append(dir)
           .append(", p.id ").append(dir)
           .append(" LIMIT ").append(size + 1);
        return sql.toString();
    }

    /** 游标语义：time 用 id；非 time 用排序列的「上页末值」。前端统一传回 lastId 即可 */
    private Long calcNextCursor(List<Post> page, boolean hasMore, boolean byTime, String by) {
        if (!hasMore) {
            return null;
        }
        if (byTime) {
            return page.get(page.size() - 1).getId();
        }
        Post last = page.get(page.size() - 1);
        return switch (by.toLowerCase()) {
            case "like" -> (long) (last.getLikeCount() == null ? 0 : last.getLikeCount());
            case "comment" -> (long) (last.getCommentCount() == null ? 0 : last.getCommentCount());
            case "view" -> (long) (last.getViewCount() == null ? 0 : last.getViewCount());
            default -> last.getId();
        };
    }
}
