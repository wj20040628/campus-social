package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.NotifyService;
import com.campus.social.service.RoleService;
import com.campus.social.util.SensitiveWordFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.social.service.PostService;
import com.campus.social.service.ReviewService;
import com.campus.social.vo.ReviewTaskVO;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import com.campus.social.assembler.UserVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CircleMapper circleMapper;
    private final RoleService roleService;
    private final PostService postService;
    private final NotifyService notifyService;
    private final NotifyMapper notifyMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;
    private final AuditService auditService;
    private final ObjectMapper om;
    private final SensitiveWordFilter sensitive;

    /**
     * 圈主协审升级窗口(小时,2026-10-24):圈内任务超过该时长未处理即滑入平台队列。
     * 截止线 = now - N 小时;「升级」不落列不起 Job,纯查询期计算。
     */
    @org.springframework.beans.factory.annotation.Value("${campus.review.owner-escalate-hours:24}")
    private int ownerEscalateHours;

    private java.time.LocalDateTime escalateCutoff() {
        return java.time.LocalDateTime.now().minusHours(ownerEscalateHours);
    }

    @Override
    public CursorPage<ReviewTaskVO> pending(long reviewerId, Long lastId, int size) {
        /*
         * 双队列(2026-10-24 圈主协审):
         *  - 管理员 → 平台队列:非圈内任务 + 已升级(超窗)的圈内任务
         *  - 非管理员 → 圈主队列:自己 owned 圈内的未升级任务(普通人自然为空)
         * 管理员不在此看未升级的圈内任务——派给圈主的活不重复出现在平台队列,
         * 这是「协审减负」的全部意义;管理员真要处理,可从圈子管理入口进(权限恒放行)。
         */
        if (roleService.isAdmin(reviewerId)) {
            java.time.LocalDateTime cutoff = escalateCutoff();
            List<ReviewTask> rows = reviewTaskMapper.selectPendingPlatform(cutoff, lastId, size + 1);
            CursorPage<ReviewTaskVO> page = pageOf(rows, size);
            Long total = (long) reviewTaskMapper.countPendingPlatform(cutoff);
            return new CursorPage<>(page.getList(), page.getNextCursor(), page.isHasMore(), total);
        }
        java.time.LocalDateTime cutoff = escalateCutoff();
        List<ReviewTask> rows = reviewTaskMapper.selectPendingForOwner(reviewerId, cutoff, lastId, size + 1);
        CursorPage<ReviewTaskVO> page = pageOf(rows, size);
        Long total = (long) reviewTaskMapper.countPendingForOwner(reviewerId, cutoff);
        return new CursorPage<>(page.getList(), page.getNextCursor(), page.isHasMore(), total);
    }

    @Override
    public CursorPage<ReviewTaskVO> processed(long reviewerId, Long lastId, int size) {
        /*
         * 已处理 = status IN (1 已通过, 2 已驳回)。2026-10-24 修：
         * 原实现把 null 传给 listByStatus —— 不过滤状态，一个叫「已处理」的
         * 接口实际把待审任务也混着返回，按 id 倒序时最新待审还会顶在最上面。
         */
        return listPage(new LambdaQueryWrapper<ReviewTask>()
                .in(ReviewTask::getStatus, 1, 2), lastId, size);
    }

    @Override
    public CursorPage<com.campus.social.vo.MyReviewVO> myReviews(long authorId, Long lastId, int size) {
        // 游标:取 size+1 判 hasMore;mapper 已按 author 关联 post.author_id
        List<ReviewTask> rows = reviewTaskMapper.selectByAuthor(authorId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<ReviewTask> page = rows.stream().limit(size).toList();

        List<com.campus.social.vo.MyReviewVO> vos = page.stream().map(t -> {
            // 结论文案:status=1 通过 / 2 驳回 / 0 审核中;驳回时展示可操作理由
            com.campus.social.vo.MyReviewVO vo = com.campus.social.vo.MyReviewVO.builder()
                    .postId(t.getPostId())
                    .status(t.getStatus())
                    .hitWords(parseHitWords(t.getHitWords()))
                    .reason(t.getReason())
                    .aiVerdict(t.getAiVerdict())
                    .aiReason(t.getAiReason())
                    .createTime(t.getCreateTime())
                    .build();
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    private CursorPage<ReviewTaskVO> listPage(LambdaQueryWrapper<ReviewTask> qw, Long lastId, int size) {
        qw.lt(lastId != null, ReviewTask::getId, lastId)
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT " + (size + 1));
        return pageOf(reviewTaskMapper.selectList(qw), size);
    }

    /** 行列表 → 游标分页(mapper 已取 size+1,这里判 hasMore 并裁剪;双队列共用) */
    private CursorPage<ReviewTaskVO> pageOf(List<ReviewTask> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<ReviewTask> page = rows.stream().limit(size).toList();
        List<ReviewTaskVO> vos = toVos(page);
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /**
     * 处置权限(2026-10-24 圈主协审):平台管理员,或该帖所属圈的圈主。
     * 圈管理(role=2)与普通成员不放行——协审责任绑定圈主一人,权责才对得上。
     * 不校验升级窗口:超窗任务滑入平台队列后,圈主若仍先一步处理,
     * casMarkProcessed 的条件更新保证只有一人生效,无并发风险。
     */
    private void requireReviewAuthority(long me, Long postId) {
        if (roleService.isAdmin(me)) {
            return;
        }
        Post post = postMapper.selectById(postId);
        if (post != null && post.getCircleId() != null) {
            Circle c = circleMapper.selectById(post.getCircleId());
            if (c != null && Long.valueOf(me).equals(c.getOwnerId())) {
                return;
            }
        }
        throw new BusinessException(ResultCode.NOT_ADMIN, "只有平台管理员或圈主可以处理这条审核");
    }

    /**
     * 批量组装 VO（2026-10-24 改）。
     *
     * <p>原 {@code page.stream().map(this::toVo)} 是逐条两次单查
     * （post + author），一页 20 条 = 40 次查询。AuditServiceImpl 的操作人
     * 回填、ReportAdminServiceImpl 的摘要分派都做过批量或明确了 N+1 取舍，
     * 这里对齐项目纪律：两次 IN 查询换掉 2N 次单查。
     */
    private List<ReviewTaskVO> toVos(List<ReviewTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, Post> posts = postMapper.selectBatchIds(
                        tasks.stream().map(ReviewTask::getPostId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Post::getId, p -> p));
        java.util.Set<Long> authorIds = posts.values().stream()
                .map(Post::getAuthorId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, User> users = authorIds.isEmpty() ? java.util.Map.of()
                : userMapper.selectBatchIds(authorIds).stream()
                        .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return tasks.stream()
                .map(t -> buildVo(t, posts.get(t.getPostId()),
                        posts.get(t.getPostId()) == null ? null
                                : users.get(posts.get(t.getPostId()).getAuthorId())))
                .toList();
    }

    private ReviewTaskVO buildVo(ReviewTask task, Post post, User author) {
        return ReviewTaskVO.builder()
                .id(task.getId())
                .postId(task.getPostId())
                .content(post != null ? post.getContent() : "(帖子已删)")
                .media(post != null ? post.getMedia() : null)
                .author(UserVoAssembler.of(author))
                .status(task.getStatus())
                .reviewerId(task.getReviewerId())
                .reason(task.getReason())
                .hitWords(parseHitWords(task.getHitWords()))
                .aiVerdict(task.getAiVerdict())
                .aiReason(task.getAiReason())
                .aiReviewedAt(task.getAiReviewedAt())
                .createTime(task.getCreateTime())
                .build();
    }

    @Override
    @Transactional
    public void approve(long taskId, long reviewerId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(NOT_FOUND, "审核任务不存在");
        }
        Post post = postMapper.selectById(task.getPostId());
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND, "帖子已被删除");
        }
        // 权限:管理员或该圈圈主(2026-10-24 圈主协审;占坑前校验,防越权进 CAS)
        requireReviewAuthority(reviewerId, post.getId());
        // 1. 占坑:条件 UPDATE status=0→1(2026-10-23 修,审查 P0-7)。
        // 旧写法 select→判→updateById 在两个管理员(或 AI+人审)并发时都能通过判重,
        // 各自扩散一次 → 同帖双进 Feed、通知双发。影响行=0 即已被处理,拒绝。
        if (reviewTaskMapper.casMarkProcessed(taskId, 1, reviewerId) == 0) {
            throw new BusinessException(ResultCode.UNPROCESSABLE, "该任务已处理");
        }

        // 2. 帖子状态 0 → 1(通过)。只更新 status 列,不整行回写 ——
        // 快照里的 like/comment/view 计数可能在 select 之后已被并发点赞改过,
        // updateById 全字段回写会把别人的计数抹掉(审查 P1-14)。
        postMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, post.getId())
                .set(Post::getStatus, 1)
                .set(Post::getUpdateTime, java.time.LocalDateTime.now()));

        // 3. 写扩散:此时才进粉丝 Feed
        postService.fanoutOnApproved(post.getId());

        // 4. 审计
        auditService.record(reviewerId, "review_approve", "post", post.getId(), "帖子通过审核", null);

        // 5. 通知作者:审核通过(type=7)
        pushReviewNotify(post.getAuthorId(), 7, post, reviewerId);

        log.info("review approve: task={}, post={}, reviewer={}", taskId, task.getPostId(), reviewerId);
    }

    @Override
    @Transactional
    public void reject(long taskId, long reviewerId, String reason) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(NOT_FOUND, "审核任务不存在");
        }
        Post post = postMapper.selectById(task.getPostId());
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND, "帖子已被删除");
        }
        // 权限:同 approve(管理员或圈主)
        requireReviewAuthority(reviewerId, post.getId());
        // 1. 占坑:同 approve(审查 P0-7),驳回理由随后单独 UPDATE 该列
        if (reviewTaskMapper.casMarkProcessed(taskId, 2, reviewerId) == 0) {
            throw new BusinessException(ResultCode.UNPROCESSABLE, "该任务已处理");
        }
        if (reason != null) {
            reviewTaskMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReviewTask>()
                    .eq(ReviewTask::getId, taskId)
                    .set(ReviewTask::getReason, reason));
        }

        // 2. 帖子状态 0 → 2(下架)。只更新 status 列(审查 P1-14,同 approve)
        postMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, post.getId())
                .set(Post::getStatus, 2)
                .set(Post::getUpdateTime, java.time.LocalDateTime.now()));

        // 3. 审计
        auditService.record(reviewerId, "review_reject", "post", post.getId(),
                "帖子驳回: " + reason, null);

        // 4. 通知作者:审核驳回(type=8)
        pushReviewNotify(post.getAuthorId(), 8, post, reviewerId);

        log.info("review reject: task={}, post={}, reason={}", taskId, task.getPostId(), reason);
    }

    /** 审核结果通知作者:type=7通过/8驳回;targetId=postId(跳详情),actorId=审核员 */
    private void pushReviewNotify(long authorId, int type, Post post, long reviewerId) {
        Notify n = new Notify();
        n.setUserId(authorId);
        n.setType(type);
        n.setTargetId(post.getId());
        n.setPostId(post.getId());
        n.setActorId(reviewerId);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(authorId, 1);
        wsAfterCommit.run(() -> wsPush.pushNotify(authorId, n.getId(), type, post.getId(), reviewerId));
    }

    /** 解析 review_task.hit_words(Java List.toString 形式 [a, b] 或 JSON)为 List<String> */
    private List<String> parseHitWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            String s = raw.trim();
            // 兼容 JSON 数组
            if (s.startsWith("[")) {
                return om.readValue(s, new TypeReference<List<String>>() {});
            }
            // Java List.toString 形式:[word1, word2]
            String inner = s.substring(1, s.length() - 1);
            if (inner.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(x -> x.startsWith("\"") && x.endsWith("\"") ? x.substring(1, x.length() - 1) : x)
                    .filter(x -> !x.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("parse hit_words fail: {}, err={}", raw, e.getMessage());
            return List.of();
        }
    }
}
