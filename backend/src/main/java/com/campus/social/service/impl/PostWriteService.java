package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.campus.social.common.ResultCode.*;

/**
 * 帖子写路径（从 PostServiceImpl 拆出）。
 *
 * <p><b>为什么拆</b>：发帖与编辑共享一整套「敏感词分级 → 审核入队 → AI 机审 → 写扩散 →
 * 话题幂等绑定」流水线，这套流水线横跨 create / edit / 审核通过三个入口，
 * 是全项目最容易被改坏的一段。原实现把它埋在 900 行的类里，
 * 改「命中 level-1 后要不要发通知」要翻 600 行。
 *
 * <p><b>敏感词两级处理是本类的核心</b>：拒发词直接报错不落库；
 * 替换词（level-1）落库但 status=0 送人工审核，DB 存<em>原文</em>，
 * 展示层才做替换——这样审核台能看到真实文本，替换只在用户侧生效。
 *
 * <p>事务后机审触发与通知下发在 {@link PostNotifySupport}，本类不重复实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostWriteService {

    private static final int NOTIFY_TYPE_REVIEWING = 6; // 内容待审(作者)

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final SensitiveWordFilter sensitive;
    private final ReviewTaskMapper reviewTaskMapper;
    private final AuditService auditService;
    private final PostNotifySupport notifySupport;
    /** 写扩散独立成服务,发帖与审核通过共用 */
    private final FeedFanoutService fanout;
    /** 话题绑定与 media 校验,已抽出 */
    private final PostTopicBinder topicBinder;

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

        String content = dto.getContent() == null ? "" : dto.getContent();
        if (sensitive.containsBlock(content)) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED);
        }
        // level1 命中:送人工审核(status=0,不入 Feed)
        boolean needsReview = sensitive.containsReplace(content);
        List<String> hitWords = needsReview ? sensitive.matchedLevel1Words(content) : List.of();

        // 解析校验 media JSON
        topicBinder.parseMedia(dto.getMedia());

        requireCircleMemberIfNeeded(dto.getCircleId(), authorId);

        Post post = new Post();
        post.setAuthorId(authorId);
        post.setContent(content);
        // media 已经过 parseMedia 校验为合法 JSON 数组,直接存原字符串(避免再序列化抛受检异常)
        post.setMedia(dto.getMedia());
        post.setStatus(needsReview ? 0 : 1);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setViewCount(0);
        post.setCircleId(dto.getCircleId());
        post.setHotScore(0.0);
        postMapper.insert(post);

        topicBinder.bind(post.getId(), dto.getTopics());

        if (needsReview) {
            enqueueReview(post.getId(), hitWords, false);
            notifySupport.pushNotify(authorId, NOTIFY_TYPE_REVIEWING, post.getId(), authorId, post.getId());
            log.info("post flagged for review: id={}, hitWords={}", post.getId(), hitWords);
            notifySupport.scheduleAiReviewAfterCommit(post.getId(), content, dto.getMedia());
        }

        // 写扩散:仅审核通过的帖子进 Feed(审核中的帖子被 Feed 查询 status!=1 过滤)
        if (!needsReview) {
            fanout.fanout(authorId, post.getId());
        }

        log.info("post created: id={}, author={}, review={}", post.getId(), authorId, needsReview);
        auditService.record(authorId, "create_post", "post", post.getId(),
                needsReview ? "含敏感词,送审" : "直接通过", null);
        return post.getId();
    }

    /**
     * 编辑帖子。
     *
     * <p><b>本方法永远返回 null，这是刻意设计，不是未实现的坑</b>。
     * 调用方（{@code PostServiceImpl#edit}）拿到 null 后统一回详情。
     *
     * <p>为什么不返回 PostVO：write 层一旦回详情 VO，就被迫依赖
     * {@link PostVoAssembler} 的装配逻辑，而装配是读侧关注点。
     * 让调用方回详情可以保持写路径与读装配的职责边界。
     *
     * <p>为什么不返回 {@code Optional<PostVO>}：Optional 表达「可能没有值」，
     * 是预期内的分支；而这里的语义是「本次调用只做了校验与落库，
     * 不负责回读」——这是一个明确的状态，不是值缺失。
     * 用 Optional 会诱导调用方写出 map/orElseGet 链，把两种语义搅在一起。
     *
     * <p><b>失败路径一律抛异常而非返回 null</b>：帖子不存在、非作者、帖子已删除、
     * 内容为空、含拒发敏感词，这五种都抛 {@link BusinessException}。
     * 也就是说 null 唯一意味着「校验通过且已落库，需要调用方回读」，
     * 调用方不需要区分成功与失败，只需在 null 时回详情。
     */
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
        boolean contentChanged = !Objects.equals(
                post.getContent(), dto.getContent() == null ? "" : dto.getContent());
        boolean mediaChanged = !Objects.equals(
                post.getMedia(), dto.getMedia() == null ? null : dto.getMedia());
        // topics: dto 为 null 视为「不变」,空数组视为「删全部」(与 create 口径一致)
        boolean topicsChanged = dto.getTopics() != null
                && !Objects.equals(
                        topicBinder.topicIdsOf(postId).stream().map(String::valueOf).toList(),
                        dto.getTopics().stream().map(String::valueOf).toList());
        if (!contentChanged && !mediaChanged && !topicsChanged) {
            // null = 无需变更,调用方按 postId 回读详情即可(与下面的正常落库返回同一含义)
            return null;
        }

        // 文案兜底:允许 content=null 表示「保留原内容」(前端只改话题/媒体的场景)
        String newContent = dto.getContent() == null ? post.getContent() : dto.getContent();
        if (newContent.isBlank()
                && (dto.getMedia() == null || dto.getMedia().isBlank())
                && (post.getMedia() == null || post.getMedia().isBlank())) {
            throw new BusinessException(BAD_REQUEST, "内容不能为空(需文案或媒体)");
        }

        if (sensitive.containsBlock(newContent)) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "编辑内容含拒发敏感词");
        }
        // 只在文案真的变了才重审:仅改话题不该把已过审的帖子打回审核队列
        boolean needsReview = contentChanged && sensitive.containsReplace(newContent);
        List<String> hitWords = needsReview ? sensitive.matchedLevel1Words(newContent) : List.of();

        post.setContent(newContent);
        post.setMedia(dto.getMedia() == null ? post.getMedia() : dto.getMedia());
        if (needsReview) {
            post.setStatus(0);
        }
        postMapper.updateById(post);

        if (topicsChanged) {
            List<Long> oldTopicIds = topicBinder.topicIdsOf(postId);
            topicBinder.softDeleteOf(postId);
            topicBinder.decrementCounts(oldTopicIds);
            topicBinder.bind(postId, dto.getTopics());
        }

        if (needsReview) {
            enqueueReview(postId, hitWords, true);
            notifySupport.pushNotify(authorId, NOTIFY_TYPE_REVIEWING, postId, authorId, postId);
            notifySupport.scheduleAiReviewAfterCommit(postId, newContent, post.getMedia());
        }

        log.info("post edited: id={}, author={}, content={}, media={}, topics={}, review={}",
                postId, authorId, contentChanged, mediaChanged, topicsChanged, needsReview);
        auditService.record(authorId, "edit_post", "post", postId,
                "编辑帖子" + (needsReview ? "(命中敏感词,重回审核)" : ""), null);
        // null = 已落库,不是「编辑失败」。调用方按 postId 回读详情
        return null;
    }

    /** 圈子发帖权限:带 circleId 时必须是圈内成员 */
    private void requireCircleMemberIfNeeded(Long circleId, long authorId) {
        if (circleId == null) {
            return;
        }
        Long memberCount = circleMemberMapper.selectCount(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, authorId));
        if (memberCount != 0) {
            return;
        }
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
        }
        throw new BusinessException(UNPROCESSABLE, "请先加入该圈子再发帖");
    }

    /**
     * 审核入队。
     *
     * <p>{@code isEdit} 为 true 时走「有则更新、无则插入」而不是直接插：
     * review_task 上有 uk_post 唯一约束，编辑重审时直接插会撞唯一键报错，
     * 把用户的一次正常编辑变成 500。顺带把 reviewer / reason 清空——
     * 之前的审核结论对改过的内容已经无效。
     */
    private void enqueueReview(long postId, List<String> hitWords, boolean isEdit) {
        List<ReviewTask> existing = isEdit
                ? reviewTaskMapper.selectList(new LambdaQueryWrapper<ReviewTask>().eq(ReviewTask::getPostId, postId))
                : List.of();
        if (existing.isEmpty()) {
            ReviewTask task = new ReviewTask();
            task.setPostId(postId);
            task.setStatus(0);
            task.setHitWords(hitWords.isEmpty() ? null : hitWords.toString());
            reviewTaskMapper.insert(task);
            return;
        }
        ReviewTask t = existing.get(0);
        t.setStatus(0);
        t.setReviewerId(null);
        t.setReason(null);
        t.setHitWords(hitWords.isEmpty() ? null : hitWords.toString());
        reviewTaskMapper.updateById(t);
    }
}
