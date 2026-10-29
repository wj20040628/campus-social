package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.Post;
import com.campus.social.entity.Topic;
import com.campus.social.mapper.FeedItemMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.PostTopicMapper;
import com.campus.social.mapper.TopicMapper;
import com.campus.social.mapper.UserTagMapper;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.RecommendFeedVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
    private final com.campus.social.mapper.RelationMapper relationMapper;
    private final UserTagMapper userTagMapper;
    /** 首页第一页的公共骨架缓存；只在 lastId 为空且无话题过滤时用 */
    private final FeedPageCacheService feedCache;

    /**
     * 综合 Feed。
     *
     * <p>by: time(默认) | like | comment | view；order: desc(默认) | asc；topic 可选；
     * anonymous 可选(true = 只看树洞匿名帖)。
     * 游标用「排序列 + id」做 tie-break，保证同值时翻页稳定不重不漏。
     *
     * <p><b>首屏走缓存（2026-10-22）</b>：只有「第一页 + 无话题过滤」进缓存，
     * 因为首屏是唯一被所有人反复请求的页；翻页各有各的进度，缓存命中率极低。
     * 缓存的只是与「谁在看」无关的公共骨架，用户态每次实时回填。
     * 匿名过滤(2026-10-25,树洞 tab)不进缓存:过滤维度要进缓存 key,
     * 共享骨架的命中收益cover不了复杂度,树洞流量远小于全部流,实时查即可。
     */
    public CursorPage<PostVO> feed(long viewerId, Long lastId, int size, String by, String order,
                                   String topic, Boolean anonymous) {
        boolean asc = "asc".equalsIgnoreCase(order);
        boolean byTime = by == null || "time".equalsIgnoreCase(by);
        boolean anonymousOnly = Boolean.TRUE.equals(anonymous);

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
        boolean cacheable = lastId == null && !filterByTopic && !anonymousOnly;

        /*
         * 拉黑用户不走首屏缓存(2026-10-24):缓存是全员共享的骨架,
         * 里面的帖子没按 viewer 过滤拉黑作者——拉黑者命中缓存会看到
         * 刚拉黑的人的帖子(实测:拉黑后首页首屏仍显示 WJ 帖)。
         * 有拉黑关系的人数量极少,让他们走实时 SQL(NOT EXISTS 过滤),
         * 缓存命中率几乎不受影响。viewerId=0 是缓存装配用的公共视角,恒无拉黑。
         */
        boolean hasBlacklist = viewerId > 0 && relationMapper.existsAnyBlacklist(viewerId);
        if (cacheable && !hasBlacklist) {
            FeedPageCacheService.Snapshot snap = feedCache.read(by, order, size);
            if (snap != null && snap.getItems() != null) {
                assembler.fillViewerState(snap.getItems(), viewerId);
                return new CursorPage<>(snap.getItems(), snap.getNextCursor(), snap.isHasMore());
            }
        }

        List<Post> rows;
        // 快速路径:仅「按时间 + 倒序 + 无话题 + 无匿名过滤」走 Lambda(最常见默认场景);
        // 其余都走 SQL 分支,这样 order 才真正生效
        if (byTime && !asc && !filterByTopic && !hasBlacklist && !anonymousOnly) {
            // 快速路径仅限无拉黑者(见上方注释);有拉黑走 SQL 分支带 NOT EXISTS
            rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, 1)
                    .lt(lastId != null, Post::getId, lastId)
                    .orderByDesc(Post::getId)
                    .last("LIMIT " + (size + 1)));
        } else {
            rows = postMapper.selectFeedBySql(buildFeedSql(sortCol, dir, byTime, asc, topicId,
                    filterByTopic, lastId, size, viewerId, anonymousOnly));
        }

        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        Long nextCursor = calcNextCursor(page, hasMore, byTime, by);

        // 按 viewerId=0 装配公共骨架（liked/favorited 恒 false），与「谁在看」无关
        List<PostVO> vos = assembler.toVoList(page, 0L);

        // 先缓存、后回填用户态，顺序不可颠倒：
        // 反过来会把当前用户的「已赞」写进共享缓存，下一个访问者会看到不属于他的点赞状态。
        if (cacheable) {
            feedCache.write(by, order, size, vos, nextCursor, hasMore);
        }
        assembler.fillViewerState(vos, viewerId);
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /**
     * 推荐流(2026-10-27)。
     *
     * <p>排序规则:
     * 1. 作者与当前登录人的共同兴趣标签数(COUNT DISTINCT,每个标签等权)降序;
     * 2. 同分按帖子 id 降序(即新帖优先);
     * 3. 当前登录人没有任何标签时,所有人得分都是 0,自然退化成最新流。
     *
     * <p>排除:自己、匿名帖、拉黑关系(双向)、未过审、已删除。
     * 不做「按内容猜兴趣」的算法——PRD §9 明确排除 AI 匹配,这里只是把
     * 用户自己选过的标签做集合交集。
     *
     * <p>游标 {@code (lastScore,lastId)} 必须成对使用:只传 id 会在同分处
     * 跳页或重复。变量全部来自服务端查库的 Long / 数值,前端字符串不拼进 SQL。
     */
    public RecommendFeedVO recommend(long viewerId, Integer lastScore, Long lastId, int size) {
        List<Long> tagIds = userTagMapper.selectTagIdsByUser(viewerId);
        String scoreExpr = "0";
        if (tagIds != null && !tagIds.isEmpty()) {
            String in = tagIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            scoreExpr = "(SELECT COUNT(DISTINCT ut.tag_id) FROM user_tag ut "
                    + "WHERE ut.user_id = p.author_id AND ut.is_deleted = 0 "
                    + "AND ut.tag_id IN (" + in + "))";
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM (")
                .append("SELECT p.*, ").append(scoreExpr).append(" AS shared_tag_count FROM post p ")
                .append("WHERE p.status = 1 AND p.is_deleted = 0 AND COALESCE(p.is_anonymous, 0) = 0 ")
                .append("AND p.author_id <> ").append(viewerId).append(' ')
                .append("AND NOT EXISTS (SELECT 1 FROM relation r WHERE r.is_deleted = 0 ")
                .append("AND r.type = 3 AND ((r.from_user_id = ").append(viewerId)
                .append(" AND r.to_user_id = p.author_id) OR (r.from_user_id = p.author_id AND r.to_user_id = ")
                .append(viewerId).append("))) ");
        if (lastScore != null && lastId != null) {
            sql.append(") t WHERE (t.shared_tag_count < ").append(lastScore)
                    .append(" OR (t.shared_tag_count = ").append(lastScore)
                    .append(" AND t.id < ").append(lastId).append(")) ");
        } else {
            sql.append(") t ");
        }
        sql.append("ORDER BY t.shared_tag_count DESC, t.id DESC LIMIT ").append(size + 1);

        List<Post> rows = postMapper.selectRecommendBySql(sql.toString());
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        if (page.isEmpty()) {
            return RecommendFeedVO.builder().list(List.of()).hasMore(false).build();
        }
        Post last = page.get(page.size() - 1);
        return RecommendFeedVO.builder()
                .list(assembler.toVoList(page, viewerId))
                .hasMore(hasMore)
                .nextScore(hasMore ? last.getSharedTagCount() : null)
                .nextId(hasMore ? last.getId() : null)
                .build();
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
     * 某用户的帖子列表(2026-10-20 加 viewer 视角):
     * 本人看自己 → 全部未删帖子(含 待审0/通过1/驳回2),卡片按 status 区分标签;
     * 其他人看 → 只返回已通过(status=1)。此前无视角区分,他人主页接口
     * 会把作者的审核中/已驳回帖连内容一起带出去(2026-10-20 实测确认泄露)。
     * 两种视角都排除 is_deleted=1(软删)与 status=3(删除态)。
     */
    public CursorPage<PostVO> myPosts(long authorId, long viewerId, Long lastId, int size) {
        boolean self = viewerId == authorId;
        List<Post> rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, authorId)
                .in(Post::getStatus, self ? List.of(0, 1, 2) : List.of(1))
                // 树洞:他人主页不放匿名帖——列表本身就会泄露作者(2026-10-25);
                // 本人视角保留,作者靠 PostVO.mine 继续管理自己的匿名帖
                .eq(!self, Post::getIsAnonymous, 0)
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(assembler.toVoList(page, viewerId), nextCursor, hasMore);
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
                               Long topicId, boolean filterByTopic, Long lastId, int size, long viewerId,
                               boolean anonymousOnly) {
        StringBuilder sql = new StringBuilder("SELECT p.* FROM post p ");
        if (filterByTopic) {
            sql.append("JOIN post_topic pt ON pt.post_id = p.id AND pt.topic_id = ")
               .append(topicId)
               .append(" AND pt.is_deleted = 0 ");
        }
        sql.append("WHERE p.status = 1 AND p.is_deleted = 0 ");
        // 树洞 tab(2026-10-25):只看匿名帖
        if (anonymousOnly) {
            sql.append("AND p.is_anonymous = 1 ");
        }
        /*
         * 拉黑作者过滤(2026-10-24 补):前端拉黑文案承诺「之后看不到他的动态」,
         * 但 Feed SQL 此前只过滤 status/is_deleted——实测拉黑后帖子照常出现在
         * 「全部」流。relation.type=3 是拉黑(TYPE_BLACK),双向都算:我拉黑他/他拉黑我,
         * 都不该把内容推给我(单向只挡"我拉黑的"会让"拉黑我的人"继续骚扰视野)。
         * NOT EXISTS 走 relation 的索引,无黑名单时零额外成本(短路)。
         */
        sql.append("AND NOT EXISTS (SELECT 1 FROM relation r WHERE r.is_deleted = 0 ")
           .append("AND r.type = 3 AND ((r.from_user_id = ").append(viewerId)
           .append(" AND r.to_user_id = p.author_id) OR (r.from_user_id = p.author_id AND r.to_user_id = ")
           .append(viewerId).append("))) ");
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
        // null = 没有下一页。前端收到 null 即停止翻页
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
