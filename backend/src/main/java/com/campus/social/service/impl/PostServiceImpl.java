package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;

import com.campus.social.dto.CreateCommentDTO;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.AiReviewTrigger;
import com.campus.social.service.PostService;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.campus.social.websocket.WsPushService;
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
    private static final int TARGET_TYPE_COMMENT = 2;
    private static final int NOTIFY_TYPE_LIKE = 2;
    private static final int NOTIFY_TYPE_COMMENT = 3;
    private static final int NOTIFY_TYPE_REVIEWING = 6; // 内容待审(作者)
    private static final int NOTIFY_TYPE_APPROVED = 7; // 审核通过(作者)
    private static final int NOTIFY_TYPE_REJECTED = 8; // 审核驳回(作者)
    private static final int NOTIFY_TYPE_MENTION = 9;  // 评论中被 @

    private final PostMapper postMapper;
    private final FeedItemMapper feedItemMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final UserMapper userMapper;
    private final RelationMapper relationMapper;
    private final NotifyMapper notifyMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final SensitiveWordFilter sensitive;
    private final ObjectMapper om;
    private final WsPushService wsPush;
    private final com.campus.social.mapper.ReviewTaskMapper reviewTaskMapper;
    private final com.campus.social.service.AuditService auditService;
    private final com.campus.social.mapper.TopicMapper topicMapper;
    private final com.campus.social.mapper.PostTopicMapper postTopicMapper;
    private final AiReviewTrigger aiReviewTrigger;

    // ---------------------------------------------------------------- 发动态

    @Override
    @Transactional
    public long create(long authorId, CreatePostDTO dto) {
        User author = userMapper.selectById(authorId);
        if (author == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        if (author.getStatus() == 1) {
            throw new BusinessException(USER_FROZEN, "账号已冻结,无法发帖");
        }
        // 新建:content 必填(允许只传 media/topics 不行,至少要有文案或媒体)
        if ((dto.getContent() == null || dto.getContent().isBlank())
                && (dto.getMedia() == null || dto.getMedia().isBlank())) {
            throw new BusinessException(BAD_REQUEST, "内容不能为空(需文案或媒体)");
        }

        // 敏感词
        String content = dto.getContent();
        if (content == null) {
            content = ""; // 仅媒体帖:content 列存空串,media 仍有内容
        }
        boolean blocked = sensitive.containsBlock(content);
        if (blocked) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED);
        }
        // level 1 命中:送人工审核(status=0,不入 Feed)
        // content 存**原文**(replaceAll 在出参展示层做,见 toPostVo),
        // 让审核台能看到原始文本;命中词落 review_task.hit_words 供高亮。
        boolean needsReview = sensitive.containsReplace(content);
        java.util.List<String> hitWords = needsReview ? sensitive.matchedLevel1Words(content) : java.util.List.of();

        // 解析校验 media JSON
        parseMedia(dto.getMedia());

        // 圈子发帖权限:带 circleId 时必须是圈内成员
        if (dto.getCircleId() != null) {
            Long memberCount = circleMemberMapper.selectCount(new LambdaQueryWrapper<CircleMember>()
                    .eq(CircleMember::getCircleId, dto.getCircleId())
                    .eq(CircleMember::getUserId, authorId));
            if (memberCount == 0) {
                Circle c = circleMapper.selectById(dto.getCircleId());
                if (c == null || c.getStatus() == 1) {
                    throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
                }
                throw new BusinessException(UNPROCESSABLE, "请先加入该圈子再发帖");
            }
        }

        Post post = new Post();
        post.setAuthorId(authorId);
        post.setContent(content);
        // media 已经过 parseMedia 校验为合法 JSON 数组,直接存原字符串(避免再序列化抛受检异常)
        post.setMedia(dto.getMedia());
        post.setStatus(needsReview ? 0 : 1); // 命中 level1 → 审核中,不进 Feed;否则通过
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setViewCount(0);
        post.setCircleId(dto.getCircleId());
        post.setHotScore(0.0);
        postMapper.insert(post);

        // 绑定话题(#话题#):幂等 upsert topic + 写 post_topic
        bindTopics(post.getId(), dto.getTopics());

        // 命中 level1:入审核队列(由 review_task 表 + 审核后台处理)
        if (needsReview) {
            ReviewTask task = new ReviewTask();
            task.setPostId(post.getId());
            task.setStatus(0); // 待审
            task.setHitWords(hitWords.isEmpty() ? null : hitWords.toString());
            reviewTaskMapper.insert(task);
            // 通知作者:内容待审(type=6)
            pushNotify(authorId, NOTIFY_TYPE_REVIEWING, post.getId(), authorId, post.getId());
            log.info("post flagged for review: id={}, hitWords={}", post.getId(), hitWords);

            // AI 机审:【事务提交后】异步触发,不锁写事务;LLM 结论回写 review_task + 自动流转
            scheduleAiReviewAfterCommit(post.getId(), content, dto.getMedia());
        }

        // 写扩散:仅审核通过的帖子进 Feed(审核中的帖子被 Feed 查询 status!=1 过滤)
        if (!needsReview) {
            fanoutFeed(authorId, post.getId());
        }

        log.info("post created: id={}, author={}, review={}", post.getId(), authorId, needsReview);
        // 审计:发帖
        auditService.record(authorId, "create_post", "post", post.getId(),
                needsReview ? "含敏感词,送审" : "直接通过", null);
        return post.getId();
    }

    // ---------------------------------------------------------------- 编辑

    @Override
    @Transactional
    public PostVO edit(long postId, CreatePostDTO dto, long authorId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不存在");
        }
        if (!post.getAuthorId().equals(authorId)) {
            throw new BusinessException(NOT_POST_AUTHOR, "非帖子作者,无法编辑");
        }
        if (post.getStatus() == 3) {
            throw new BusinessException(UNPROCESSABLE, "帖子已删除,无法编辑");
        }

        // 至少改一项(文案/媒体/话题),否则幂等空操作
        boolean contentChanged = !java.util.Objects.equals(
                post.getContent(), dto.getContent() == null ? "" : dto.getContent());
        boolean mediaChanged = !java.util.Objects.equals(
                post.getMedia(), dto.getMedia() == null ? null : dto.getMedia());
        // topics: dto 为 null 视为"不变",空数组视为"删全部"(与 create 口径一致)
        boolean topicsChanged = dto.getTopics() != null
                && !java.util.Objects.equals(
                        postTopicMapper.topicIdsByPost(postId).stream().map(String::valueOf).toList(),
                        dto.getTopics().stream().map(t -> String.valueOf(t)).toList());
        if (!contentChanged && !mediaChanged && !topicsChanged) {
            return detail(postId, authorId); // 幂等空操作,直接回详情
        }

        // 文案兜底:允许 content=null 表示"保留原内容"(前端编辑时只改话题/媒体的场景)
        String newContent = dto.getContent() == null ? post.getContent() : dto.getContent();
        if (newContent.isBlank()
                && (dto.getMedia() == null || dto.getMedia().isBlank())
                && (post.getMedia() == null || post.getMedia().isBlank())) {
            throw new BusinessException(BAD_REQUEST, "内容不能为空(需文案或媒体)");
        }

        // 敏感词:编辑过的文案重新过 DFA;命中 level-1 → 回退 status=0 重入审核
        boolean blocked = sensitive.containsBlock(newContent);
        if (blocked) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "编辑内容含拒发敏感词");
        }
        boolean needsReview = contentChanged && sensitive.containsReplace(newContent);
        java.util.List<String> hitWords = needsReview ? sensitive.matchedLevel1Words(newContent) : java.util.List.of();

        // 1. 持久化 content / media
        post.setContent(newContent);
        post.setMedia(dto.getMedia() == null ? post.getMedia() : dto.getMedia());
        // 2. 命中 level-1 → 回退 status=0(否则保留 status=1)
        if (needsReview) {
            post.setStatus(0);
        }
        postMapper.updateById(post);

        // 3. 话题同步:删旧(软删 + topic.post_count -1)+ 加新(幂等 upsert)
        if (topicsChanged) {
            java.util.List<Long> oldTopicIds = postTopicMapper.topicIdsByPost(postId);
            postTopicMapper.softDeleteByPost(postId);
            for (Long tid : oldTopicIds) {
                topicMapper.decrementPostCount(tid);
            }
            bindTopics(postId, dto.getTopics());
        }

        // 4. 命中 level-1:入 review_task(幂等:已存在则更新 hit_words,避免 uk_post 冲突)
        if (needsReview) {
            java.util.List<ReviewTask> existing = reviewTaskMapper.selectList(
                    new LambdaQueryWrapper<ReviewTask>().eq(ReviewTask::getPostId, postId));
            if (existing.isEmpty()) {
                ReviewTask task = new ReviewTask();
                task.setPostId(postId);
                task.setStatus(0);
                task.setHitWords(hitWords.isEmpty() ? null : hitWords.toString());
                reviewTaskMapper.insert(task);
            } else {
                ReviewTask t = existing.get(0);
                t.setStatus(0);
                t.setReviewerId(null);
                t.setReason(null);
                t.setHitWords(hitWords.isEmpty() ? null : hitWords.toString());
                reviewTaskMapper.updateById(t);
            }
            // 通知作者:内容重审
            pushNotify(authorId, NOTIFY_TYPE_REVIEWING, postId, authorId, postId);

            // AI 机审:事务提交后异步触发(重审帖同样让 LLM 预筛,结论回写 task)
            scheduleAiReviewAfterCommit(postId, newContent, post.getMedia());
        }

        log.info("post edited: id={}, author={}, content={}, media={}, topics={}, review={}",
                postId, authorId, contentChanged, mediaChanged, topicsChanged, needsReview);
        auditService.record(authorId, "edit_post", "post", postId,
                "编辑帖子" + (needsReview ? "(命中敏感词,重回审核)" : ""), null);

        return detail(postId, authorId);
    }

    /** 写扩散:查 author 的 follower,批量 INSERT feed_item */
    private void fanoutFeed(long authorId, long postId) {
        // 我关注的人,即"粉丝"是那些 to_user_id=authorId 的 from_user_id
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getToUserId, authorId)
                .eq(Relation::getType, 1); // type=1 关注
        List<Relation> fans = relationMapper.selectList(qw);

        // 分批 INSERT
        int total = fans.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<Relation> batch = fans.subList(i, Math.min(i + BATCH_SIZE, total));
            for (Relation fan : batch) {
                FeedItem item = new FeedItem();
                item.setFanId(fan.getFromUserId());
                item.setAuthorId(authorId);
                item.setPostId(postId);
                try {
                    feedItemMapper.insert(item);
                } catch (Exception e) {
                    // 单条失败不影响其他,记日志(后续接 MQ 死信)
                    log.warn("feed fanout fail: fan={}, post={}, err={}",
                            fan.getFromUserId(), postId, e.getMessage());
                }
            }
        }
        log.info("feed fanout: post={}, fans={}", postId, total);
    }

    /** 绑定帖子话题:逐个 upsert topic(ON DUPLICATE 累加 post_count)+ 写 post_topic(幂等键 uk_pt) */
    private void bindTopics(long postId, List<String> topicNames) {
        if (topicNames == null || topicNames.isEmpty()) {
            return;
        }
        // 去重 + 去空 + 去两侧 # 与空白
        List<String> clean = topicNames.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim())
                .filter(n -> !n.isBlank())
                .distinct()
                .toList();
        for (String name : clean) {
            if (name.length() > 64) {
                continue; // 话题名超长丢弃,防脏
            }
            topicMapper.upsertByName(name);
            Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>()
                    .eq(Topic::getName, name));
            if (t == null) {
                continue;
            }
            long cnt = postTopicMapper.selectCount(new LambdaQueryWrapper<PostTopic>()
                    .eq(PostTopic::getPostId, postId)
                    .eq(PostTopic::getTopicId, t.getId()));
            if (cnt == 0) {
                PostTopic pt = new PostTopic();
                pt.setPostId(postId);
                pt.setTopicId(t.getId());
                postTopicMapper.insert(pt);
            }
        }
        log.info("post topics bound: post={}, topics={}", postId, clean);
    }

    /** 取一批帖子各自的话题名(Feed 回填,避免逐帖查) */
    private java.util.Map<Long, List<String>> topicsOfPosts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        List<PostTopic> links = postTopicMapper.listByPostIds(postIds);
        if (links.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        List<Long> topicIds = links.stream().map(PostTopic::getTopicId).distinct().toList();
        List<Topic> topics = topicMapper.selectBatchIds(topicIds);
        java.util.Map<Long, String> idToName = new java.util.HashMap<>();
        for (Topic t : topics) {
            idToName.put(t.getId(), t.getName());
        }
        java.util.Map<Long, List<String>> result = new java.util.HashMap<>();
        for (PostTopic l : links) {
            String name = idToName.get(l.getTopicId());
            if (name != null) {
                result.computeIfAbsent(l.getPostId(), k -> new java.util.ArrayList<>()).add(name);
            }
        }
        return result;
    }

    private JsonNode parseMedia(String mediaJson) {
        if (mediaJson == null || mediaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = om.readTree(mediaJson);
            if (node.isArray()) {
                // 校验每项 type 是 img/video
                for (JsonNode item : node) {
                    String type = item.path("type").asText();
                    if (!"img".equals(type) && !"video".equals(type)) {
                        throw new BusinessException(BAD_REQUEST.getCode(), "media.type 必须是 img 或 video");
                    }
                    if (item.path("url").asText().isBlank()) {
                        throw new BusinessException(BAD_REQUEST.getCode(), "media.url 不能为空");
                    }
                }
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(BAD_REQUEST.getCode(), "media 不是合法 JSON 数组");
        }
    }

    // ---------------------------------------------------------------- 详情

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
        PostVO vo = toPostVo(post, viewerId);
        fillSingleAuthor(vo, post.getAuthorId());
        // 回填话题名(单条)
        vo.setTopics(topicsOfPosts(java.util.List.of(postId)).getOrDefault(postId, java.util.List.of()));
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
        // by: time(默认,按 id/create_time)| like | comment | view
        // order: desc(默认)| asc
        // topic: 可选,按话题名筛选(JOIN post_topic + topic)
        // 游标用对应排序列 + id 做 tie-break,保证同值稳定不重不漏
        boolean asc = "asc".equalsIgnoreCase(order);
        boolean byTime = by == null || "time".equalsIgnoreCase(by);

        // 话题过滤:先查出该话题下的帖子 id 集合(有就限定,无话题参数则全量)
        Long topicId = null;
        boolean filterByTopic = topic != null && !topic.isBlank();
        if (filterByTopic) {
            Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>()
                    .eq(Topic::getName, topic.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim()));
            topicId = t != null ? t.getId() : -1L; // 查无此话题 → 强制空结果
        }

        // 组装原生 SQL(MyBatis 注解方法),用参数化占位,topic/列名走白名单校验
        String sortCol;
        if (byTime) sortCol = "p.id";
        else if ("like".equalsIgnoreCase(by)) sortCol = "p.like_count";
        else if ("comment".equalsIgnoreCase(by)) sortCol = "p.comment_count";
        else if ("view".equalsIgnoreCase(by)) sortCol = "p.view_count";
        else sortCol = "p.id";
        String dir = asc ? "ASC" : "DESC";

        // 游标值(排序列)来自上一页,由前端回传;这里 lastId 复用为"上页最后一条的排序列值"
        // 为简单稳妥:time 用 id 游标(现有),非 time 用对应列 + id tie-break
        List<Post> rows;
        // 快速路径:仅"按时间 + 倒序 + 无话题"走原 Lambda(最常见默认场景);
        // 其余(按时间正序 / 按点赞·评论·浏览 / 带话题)都走 SQL 分支,这样 order 才真正生效。
        boolean fastPath = byTime && !asc && !filterByTopic;
        if (fastPath) {
            LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, 1)
                    .lt(lastId != null, Post::getId, lastId)
                    .orderByDesc(Post::getId)
                    .last("LIMIT " + (size + 1));
            rows = postMapper.selectList(qw);
        } else {
            StringBuilder sql = new StringBuilder("SELECT p.* FROM post p ");
            if (filterByTopic) {
                sql.append("JOIN post_topic pt ON pt.post_id = p.id AND pt.topic_id = ")
                   .append(topicId)
                   .append(" AND pt.is_deleted = 0 ");
            }
            sql.append("WHERE p.status = 1 AND p.is_deleted = 0 ");
            if (byTime) {
                // id 游标 + id tie-break
                if (lastId != null) sql.append("AND p.id ").append(asc ? "> " : "< ").append(lastId).append(" ");
            } else {
                // 非 time:用排序列 + id tie-break。lastId 传的是上一页最后一条的排序列值
                if (lastId != null) {
                    // 简化:同值时继续前进用 id。这里用 (sortCol, id) 复合游标,前端需回传复合游标。
                    // MVP:仅按 sortCol 过滤,同值翻页可能重复/漏,后续可加复合游标
                    sql.append("AND p.").append(sortCol).append(" ").append(asc ? "> " : "< ").append(lastId).append(" ");
                }
            }
            sql.append("ORDER BY ").append(sortCol).append(" ").append(dir)
               .append(", p.id ").append(dir)
               .append(" LIMIT ").append(size + 1);
            rows = postMapper.selectFeedBySql(sql.toString());
        }

        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        List<PostVO> vos = page.stream().map(p -> toPostVo(p, viewerId)).toList();
        batchFillAuthors(vos, page, viewerId);
        // 回填话题名(批量,避免逐帖查)
        if (!page.isEmpty()) {
            List<Long> pids = page.stream().map(Post::getId).toList();
            java.util.Map<Long, List<String>> tmap = topicsOfPosts(pids);
            for (PostVO vo : vos) {
                vo.setTopics(tmap.getOrDefault(vo.getId(), java.util.List.of()));
            }
        }
        // 游标语义:time 用 id;非 time 用排序列的"上页末值"。前端统一传回 lastId 字段即可。
        Long nextCursor;
        if (!hasMore) {
            nextCursor = null;
        } else if (byTime) {
            nextCursor = page.get(page.size() - 1).getId();
        } else {
            Post last = page.get(page.size() - 1);
            nextCursor = switch (by.toLowerCase()) {
                case "like" -> (long) (last.getLikeCount() == null ? 0 : last.getLikeCount());
                case "comment" -> (long) (last.getCommentCount() == null ? 0 : last.getCommentCount());
                case "view" -> (long) (last.getViewCount() == null ? 0 : last.getViewCount());
                default -> last.getId();
            };
        }
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<PostVO> feedByTopic(String topicName, Long lastId, int size, long viewerId) {
        // 话题落地页:该话题下所有已通过帖子,按时间倒序 + id 游标
        String clean = topicName == null ? "" : topicName.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim();
        if (clean.isEmpty()) {
            return new CursorPage<>(java.util.List.of(), null, false);
        }
        Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>().eq(Topic::getName, clean));
        if (t == null) {
            return new CursorPage<>(java.util.List.of(), null, false); // 查无此话题
        }
        List<Long> postIds = postTopicMapper.postIdsByTopic(t.getId());
        if (postIds.isEmpty()) {
            return new CursorPage<>(java.util.List.of(), null, false);
        }
        // 回查帖子,过滤 已通过/未删,按 id 倒序 + 游标
        List<Post> candidates = postMapper.selectBatchIds(postIds);
        candidates.sort((x, y) -> y.getId().compareTo(x.getId())); // id 倒序
        if (lastId != null) {
            candidates.removeIf(p -> p.getId() >= lastId);
        }
        candidates = candidates.stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == 1)
                .toList();
        boolean hasMore = candidates.size() > size;
        List<Post> page = candidates.stream().limit(size).toList();
        List<PostVO> vos = page.stream().map(p -> toPostVo(p, viewerId)).toList();
        batchFillAuthors(vos, page, viewerId);
        if (!page.isEmpty()) {
            java.util.Map<Long, List<String>> tmap = topicsOfPosts(page.stream().map(Post::getId).toList());
            for (PostVO vo : vos) {
                vo.setTopics(tmap.getOrDefault(vo.getId(), java.util.List.of()));
            }
        }
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<PostVO> feedFollowing(long viewerId, Long lastId, int size) {
        // 读我关注的人的帖子(读 feed_item 收件箱,写扩散的读侧)
        List<Post> rows = feedItemMapper.selectFeedForFan(viewerId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.subList(0, Math.min(size, rows.size()));

        List<PostVO> vos = page.stream().map(p -> toPostVo(p, viewerId)).toList();
        batchFillAuthors(vos, page, viewerId);
        if (!page.isEmpty()) {
            java.util.Map<Long, List<String>> tmap = topicsOfPosts(page.stream().map(Post::getId).toList());
            for (PostVO vo : vos) {
                vo.setTopics(tmap.getOrDefault(vo.getId(), java.util.List.of()));
            }
        }
        return new CursorPage<>(vos, hasMore ? page.get(page.size() - 1).getId() : null, hasMore);
    }

    @Override
    public CursorPage<PostVO> hot(int size, Long lastHotScore, long viewerId) {
        // 简化:按 hot_score 排序(实际应从 Redis ZSet 取 TopN 再回查)
        LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .orderByDesc(Post::getHotScore)
                .last("LIMIT " + size);
        List<Post> posts = postMapper.selectList(qw);
        List<PostVO> vos = posts.stream().map(p -> toPostVo(p, viewerId)).toList();
        batchFillAuthors(vos, posts, viewerId);
        return new CursorPage<>(vos, null, false);
    }

    // ---------------------------------------------------------------- 审核通过扩散

    @Override
    @Transactional
    public void fanoutOnApproved(long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            return; // 非通过状态不扩散
        }
        fanoutFeed(post.getAuthorId(), postId);
    }

    // ---------------------------------------------------------------- 点赞

    @Override
    @Transactional
    public void like(long postId, long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
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
            pushNotify(post.getAuthorId(), NOTIFY_TYPE_LIKE, postId, userId, postId);
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

    @Override
    @Transactional
    public long comment(long postId, CreateCommentDTO dto, long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不可评论");
        }
        // 评论也过敏感词
        String content = dto.getContent();
        if (sensitive.containsBlock(content)) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED);
        }
        content = sensitive.replaceAll(content);

        int level = dto.getLevel() == null ? 1 : dto.getLevel();
        if (level < 1 || level > 3) {
            throw new BusinessException(BAD_REQUEST.getCode(), "评论层级 1~3");
        }

        Comment c = new Comment();
        c.setPostId(postId);
        c.setUserId(userId);
        c.setParentId(dto.getParentId());
        c.setLevel(level);
        c.setReplyToUserId(dto.getReplyToUserId());
        c.setContent(content);
        c.setStatus(1);
        // 被 @ 用户:存 "昵称→id" 映射,前端据此把 @昵称 渲染成可点跳转(昵称可能重名,必须存 id)
        if (dto.getMentionUserIds() != null && !dto.getMentionUserIds().isEmpty()) {
            java.util.Map<String, Long> mentioned = new java.util.LinkedHashMap<>();
            for (Long mid : dto.getMentionUserIds().stream().distinct().toList()) {
                User mu = userMapper.selectById(mid);
                if (mu != null && mu.getStatus() != 2) {
                    mentioned.put(mu.getNickname() != null ? mu.getNickname() : mu.getUsername(), mu.getId());
                }
            }
            if (!mentioned.isEmpty()) {
                try { c.setMentioned(om.writeValueAsString(mentioned)); }
                catch (Exception ignored) {}
            }
        }
        commentMapper.insert(c);

        postMapper.adjustCommentCount(postId, 1);
        // 热度分
        postMapper.updateHotScore(postId, post.getHotScore() + 2.0);

        // 通知:
        // 一级评论(level=1) → 通知帖子作者
        // 二级(level=2,parentId 指向一级) → 通知一级评论者
        // 三级(level=3) → 通知被回复的人
        Long notifyTarget;
        if (level == 1) {
            notifyTarget = post.getAuthorId();
        } else if (level == 2 && dto.getParentId() != null) {
            Comment parent = commentMapper.selectById(dto.getParentId());
            notifyTarget = parent != null ? parent.getUserId() : post.getAuthorId();
        } else {
            notifyTarget = dto.getReplyToUserId() != null ? dto.getReplyToUserId() : post.getAuthorId();
        }
        if (!notifyTarget.equals(userId)) {
            pushNotify(notifyTarget, NOTIFY_TYPE_COMMENT, c.getId(), userId, postId);
        }

        // 被 @ 通知(type=9):按前端传来的唯一 id 精准发,昵称不唯一所以不靠正文反查。
        // 排除自己、去重、跳过已失效用户;归属人头(C3)= 被@者(接收方),不是评论作者。
        if (dto.getMentionUserIds() != null && !dto.getMentionUserIds().isEmpty()) {
            List<Long> mentionIds = dto.getMentionUserIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .filter(mid -> !mid.equals(userId))
                    .toList();
            for (Long mid : mentionIds) {
                User mentioned = userMapper.selectById(mid);
                if (mentioned != null && mentioned.getStatus() != 2) {
                    pushNotify(mid, NOTIFY_TYPE_MENTION, c.getId(), userId, postId);
                }
            }
        }
        return c.getId();
    }

    @Override
    public CursorPage<CommentVO> comments(long postId, Long lastId, int size, long viewerId) {
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getStatus, 1)
                .eq(Comment::getLevel, 1)
                .lt(lastId != null, Comment::getId, lastId)
                .orderByDesc(Comment::getId)
                .last("LIMIT " + (size + 1));
        List<Comment> rows = commentMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Comment> page = rows.stream().limit(size).toList();

        List<CommentVO> vos = page.stream().map(c -> toCommentVo(c, viewerId)).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<PostVO> myPosts(long authorId, Long lastId, int size) {
        // 作者看自己全部未删帖子(含 待审0/通过1/驳回2),卡片按 status 区分标签;
        // 排除 is_deleted=1(软删)+ status=3(删除态已物理删,理论上无)
        LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, authorId)
                .in(Post::getStatus, java.util.List.of(0, 1, 2))
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1));
        List<Post> rows = postMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();
        List<PostVO> vos = page.stream().map(p -> toPostVo(p, authorId)).toList();
        batchFillAuthors(vos, page, authorId);
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    // ---------------------------------------------------------------- 私有

    /** 批量回填作者,消除 Feed/列表逐条 selectById 的 N+1 */
    private void batchFillAuthors(List<PostVO> vos, List<Post> posts, long viewerId) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> authorIds = posts.stream()
                .map(Post::getAuthorId)
                .distinct()
                .toList();
        List<User> users = userMapper.selectBatchIds(authorIds);
        java.util.Map<Long, User> byId = new java.util.HashMap<>();
        for (User u : users) {
            byId.put(u.getId(), u);
        }
        for (PostVO vo : vos) {
            User author = byId.get(vo.getAuthorId());
            if (author != null) {
                vo.setAuthor(UserVO.builder()
                        .id(author.getId())
                        .username(author.getUsername())
                        .nickname(author.getNickname() != null ? author.getNickname() : author.getUsername())
                        .avatarUrl(author.getAvatarUrl())
                        .grade(author.getGrade())
                        .dept(author.getDept())
                        .build());
            }
        }
    }

    /**
     * 事务提交后触发 AI 机审(不锁写事务:LLM 往返在提交后、异步线程跑)。
     * 无事务时直接调;有事务时注册 afterCompletion(仅 STATUS_COMMITTED 才触发,回滚不机审)。
     */
    private void scheduleAiReviewAfterCommit(long postId, String content, String mediaJson) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        // 此时帖子 + review_task 已落库;aiReviewTrigger.moderateAndApply 是 @Async
                        aiReviewTrigger.moderateAndApply(postId, content, mediaJson);
                    }
                }
            });
        } else {
            aiReviewTrigger.moderateAndApply(postId, content, mediaJson);
        }
    }

    private void pushNotify(long userId, int type, long targetId, long actorId, Long postId) {
        Notify n = new Notify();
        n.setUserId(userId);
        n.setType(type);
        n.setTargetId(targetId);
        n.setPostId(postId);
        n.setActorId(actorId);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(userId, 1);
        // WS 实时推(离线落表,上线后 REST 拉)
        wsPush.pushNotify(userId, n.getId(), type, targetId, actorId);
    }

    /** 解析 comment.mentioned JSON 为 昵称→id 映射;解析失败返回 null(不阻塞评论展示) */
    private java.util.Map<String, Long> parseMentioned(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Long>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private PostVO toPostVo(Post p, long viewerId) {
        // 作者信息不在这里逐条查:列表场景由 batchFillAuthors 批量回填;
        // 单条 detail 场景单独 selectById 后回填,避免 N+1
        boolean liked = false;
        if (viewerId > 0) {
            Long cnt = likeMapper.selectCount(new LambdaQueryWrapper<Like>()
                    .eq(Like::getUserId, viewerId)
                    .eq(Like::getTargetType, TARGET_TYPE_POST)
                    .eq(Like::getTargetId, p.getId()));
            liked = cnt > 0;
        }
        JsonNode media = null;
        if (p.getMedia() != null && !p.getMedia().isBlank()) {
            try {
                media = om.readTree(p.getMedia());
            } catch (Exception ignored) {
            }
        }
        // 展示层替换:DB 存原文,公开展示(status=1)时把 level-1 命中词替换成 *,
        // 待审(0)/驳回(2)帖保留原文(作者/审核员看原貌)。
        String outContent = (p.getStatus() != null && p.getStatus() == 1)
                ? sensitive.replaceAll(p.getContent())
                : p.getContent();
        return PostVO.builder()
                .id(p.getId())
                .authorId(p.getAuthorId())
                .content(outContent)
                .media(media)
                .status(p.getStatus())
                .likeCount(p.getLikeCount())
                .commentCount(p.getCommentCount())
                .viewCount(p.getViewCount())
                .hotScore(p.getHotScore())
                .circleId(p.getCircleId())
                .createTime(p.getCreateTime())
                .liked(liked)
                .build();
    }

    /** 单条详情:单独查作者回填 */
    private void fillSingleAuthor(PostVO vo, long authorId) {
        User author = userMapper.selectById(authorId);
        if (author != null) {
            vo.setAuthor(UserVO.builder()
                    .id(author.getId())
                    .username(author.getUsername())
                    .nickname(author.getNickname() != null ? author.getNickname() : author.getUsername())
                    .avatarUrl(author.getAvatarUrl())
                    .grade(author.getGrade())
                    .dept(author.getDept())
                    .build());
        }
    }

    private CommentVO toCommentVo(Comment c, long viewerId) {
        User u = userMapper.selectById(c.getUserId());
        UserVO userVo = u == null ? null
                : UserVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .nickname(u.getNickname() != null ? u.getNickname() : u.getUsername())
                        .avatarUrl(u.getAvatarUrl())
                        .build();

        boolean liked = false;
        if (viewerId > 0) {
            Long cnt = likeMapper.selectCount(new LambdaQueryWrapper<Like>()
                    .eq(Like::getUserId, viewerId)
                    .eq(Like::getTargetType, TARGET_TYPE_COMMENT)
                    .eq(Like::getTargetId, c.getId()));
            liked = cnt > 0;
        }
        String replyNick = null;
        if (c.getReplyToUserId() != null) {
            User rep = userMapper.selectById(c.getReplyToUserId());
            if (rep != null) {
                replyNick = rep.getNickname() != null ? rep.getNickname() : rep.getUsername();
            }
        }
        return CommentVO.builder()
                .id(c.getId())
                .postId(c.getPostId())
                .userId(c.getUserId())
                .user(userVo)
                .parentId(c.getParentId())
                .level(c.getLevel())
                .replyToUserId(c.getReplyToUserId())
                .replyToNickname(replyNick)
                .content(c.getContent())
                .mentioned(parseMentioned(c.getMentioned()))
                .likeCount(0)
                .liked(liked)
                .createTime(c.getCreateTime())
                .build();
    }
}
