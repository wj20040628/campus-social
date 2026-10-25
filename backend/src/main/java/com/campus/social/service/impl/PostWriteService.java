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
    /** 圈内封禁拦截:发帖时查 ban_status */
    private final com.campus.social.service.CircleAdminService circleAdminService;
    /** 审核信任闸门(2026-10-24):level-1 直接命中的受信用户豁免送审 */
    private final ReviewTrustService trustService;
    /** 发帖日配额(2026-10-25):未认证 3/日,认证 30/日,Redis 自然日分桶 */
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

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
        // 日配额:内容已合法才计数——被拒发的尝试不该占用当天的额度
        guardDailyQuota(authorId);
        boolean needsReview = needsHumanReview(content, authorId);
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
        // 树洞匿名:仅创建时生效,edit 走列级 UPDATE 不碰此列,天然不可翻转
        post.setIsAnonymous(Boolean.TRUE.equals(dto.getAnonymous()) ? 1 : 0);
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
        auditService.record(authorId, "create_post", "post", post.getId(), auditDetail(content, authorId, needsReview), null);
        return post.getId();
    }

    /**
     * 发帖日配额(2026-10-25):Redis 自然日分桶(与 StrangerQuota 的日桶同构),
     * key post:daily:{uid}:{yyyyMMdd},TTL 48h 兜底过期(桶按日期隔离,长 TTL 只是防漏删)。
     * INCR 先行再判超:计数只增不减,超限的失败尝试也占额度——防脚本试探性绕过。
     */
    private void guardDailyQuota(long authorId) {
        int limit = trustService.postDailyLimit(authorId);
        String day = java.time.LocalDate.now().toString().replace("-", "");
        String key = "post:daily:" + authorId + ":" + day;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, java.time.Duration.ofHours(48));
        }
        if (n != null && n > limit) {
            throw new BusinessException(POST_DAILY_LIMIT_REACHED,
                    "今天的发帖额度(" + limit + " 条)用完了,完成认证或提升信誉分可增加额度");
        }
    }

    /**
     * level-1 命中是否需要人工审（2026-10-24 信任闸门）。
     *
     * <p>三条路径：
     * <ul>
     *   <li>未命中 → 直接发布（原行为）</li>
     *   <li><b>直接命中</b>（词表原形就在文里）且作者受信（已认证+账龄+无前科，
     *       见 {@link ReviewTrustService}）→ 替换后直接发布，留痕不进队列</li>
     *   <li>直接命中但作者不受信，或<b>仅变体命中</b>（归一化后才命中——
     *       拆字/全角/零宽，说明作者刻意变形过）→ 一律人工审。
     *       变体不走信任豁免：变形本身就是意图信号</li>
     * </ul>
     */
    private boolean needsHumanReview(String content, long authorId) {
        boolean level1 = sensitive.containsReplace(content);
        if (!level1) {
            return false;
        }
        if (sensitive.containsReplaceDirect(content) && trustService.isTrusted(authorId)) {
            return false;
        }
        return true;
    }

    /** 发帖审计明细:受信直发时把命中词写进 audit_log(队列里看不到了,留痕是唯一追诉线索) */
    private String auditDetail(String content, long authorId, boolean needsReview) {
        if (needsReview) {
            return "含敏感词,送审";
        }
        if (sensitive.containsReplace(content)) {
            return "受信用户直发(命中一级词:" + sensitive.matchedLevel1Words(content) + ")";
        }
        return "直接通过";
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
        // 只在文案真的变了才重审:仅改话题不该把已过审的帖子打回审核队列。
        // 命中后的去留走同一道信任闸门(needsHumanReview):受信用户的直接命中直发,变体恒送审
        boolean needsReview = contentChanged && needsHumanReview(newContent, authorId);
        List<String> hitWords = needsReview ? sensitive.matchedLevel1Words(newContent) : List.of();

        /*
         * 只更新真正改动的列(2026-10-23 修,审查 P1-14):
         * 旧写法 post.setXxx(...) + updateById(post) 会把快照里的所有非 null 字段写回 ——
         * 包括 like_count / comment_count / view_count / favorite_count / hot_score。
         * 编辑是高频动作,「读者正在点赞」与「作者在编辑」并发时,后者用旧快照覆盖前者,
         * 点赞数凭空消失。改成列级 UPDATE 后只碰业务确实改动的字段。
         */
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post> uw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                        .eq(Post::getId, postId)
                        .set(Post::getContent, newContent)
                        .set(Post::getMedia, dto.getMedia() == null ? post.getMedia() : dto.getMedia())
                        .set(Post::getUpdateTime, java.time.LocalDateTime.now());
        if (needsReview) {
            uw.set(Post::getStatus, 0);
        }
        postMapper.update(null, uw);

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
        String editDetail = "编辑帖子" + (needsReview ? "(命中敏感词,重回审核)" : "");
        if (!needsReview && contentChanged && sensitive.containsReplace(newContent)) {
            editDetail += "(受信直发,命中一级词:" + sensitive.matchedLevel1Words(newContent) + ")";
        }
        auditService.record(authorId, "edit_post", "post", postId, editDetail, null);
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
            // 圈内封禁拦截:普通成员被封禁则无法发帖(圈主/圈管理恒不拦,见 isBanned)
            if (circleAdminService.isBanned(circleId, authorId)) {
                throw new BusinessException(CIRCLE_MEMBER_BANNED, "你已被圈主/圈管理封禁,无法在此圈子操作");
            }
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
