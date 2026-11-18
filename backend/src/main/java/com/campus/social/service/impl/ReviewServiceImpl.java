package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.PostService;
import com.campus.social.service.ReviewService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.MyReviewVO;
import com.campus.social.vo.ReviewTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CircleMapper circleMapper;
    private final AuditLogMapper auditLogMapper;
    private final RoleService roleService;
    private final PostService postService;
    private final AuditService auditService;
    private final ReviewVoAssembler reviewVoAssembler;
    private final ReviewNotifySupport reviewNotify;

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
    public CursorPage<ReviewTaskVO> pending(long reviewerId, Long circleId, Long lastId, int size) {
        /*
         * 三条队列(2026-11-08 修):
         *  - circleId 非空 → 圈管理抽屉:该圈全部待审,管理员或圈主可看,
         *    不限升级窗口。旧实现里圈主账号若同时是平台管理员,会被按管理员
         *    分流进平台队列,而平台队列又故意排除未升级的圈内任务——两边都空。
         *  - 管理员 → 全平台待审(含圈内任务)。可见性优先于「不重复派活」:
         *    重复只会在处置时被 CAS 拦下,不可见则会让帖子永远卡在待审。
         *  - 非管理员 → 圈主队列:自己 owned 圈内未升级任务(普通人自然为空)。
         */
        if (circleId != null) {
            requireCircleQueueAuthority(reviewerId, circleId);
            List<ReviewTask> rows = reviewTaskMapper.selectPendingByCircle(circleId, lastId, size + 1);
            CursorPage<ReviewTaskVO> page = pageOf(rows, size);
            Long total = (long) reviewTaskMapper.countPendingByCircle(circleId);
            return new CursorPage<>(page.getList(), page.getNextCursor(), page.isHasMore(), total);
        }
        if (roleService.isAdmin(reviewerId)) {
            List<ReviewTask> rows = reviewTaskMapper.selectPendingAll(lastId, size + 1);
            CursorPage<ReviewTaskVO> page = pageOf(rows, size);
            Long total = (long) reviewTaskMapper.countPendingAll();
            return new CursorPage<>(page.getList(), page.getNextCursor(), page.isHasMore(), total);
        }
        java.time.LocalDateTime cutoff = escalateCutoff();
        List<ReviewTask> rows = reviewTaskMapper.selectPendingForOwner(reviewerId, cutoff, lastId, size + 1);
        CursorPage<ReviewTaskVO> page = pageOf(rows, size);
        Long total = (long) reviewTaskMapper.countPendingForOwner(reviewerId, cutoff);
        return new CursorPage<>(page.getList(), page.getNextCursor(), page.isHasMore(), total);
    }

    /** 圈内队列的权限:平台管理员,或该圈圈主。不认圈管理与普通成员。 */
    private void requireCircleQueueAuthority(long me, long circleId) {
        if (roleService.isAdmin(me)) {
            return;
        }
        Circle c = circleMapper.selectById(circleId);
        if (c != null && Long.valueOf(me).equals(c.getOwnerId())) {
            return;
        }
        throw new BusinessException(ResultCode.NOT_ADMIN, "只有平台管理员或圈主可以查看该圈待审内容");
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
    public CursorPage<MyReviewVO> myReviews(long authorId, Long lastId, int size) {
        // 游标:取 size+1 判 hasMore;mapper 已按 author 关联 post.author_id
        List<ReviewTask> rows = reviewTaskMapper.selectByAuthor(authorId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<ReviewTask> page = rows.stream().limit(size).toList();

        Set<Long> reviewerIds = page.stream().map(ReviewTask::getReviewerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> reviewerMap = reviewerIds.isEmpty() ? new HashMap<>()
                : userMapper.selectBatchIds(reviewerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));
        // 处理人身份要区分圈主/圈管理，必须带上帖子所属圈；一次 IN 查询，不做逐条单查
        Set<Long> postIds = page.stream().map(ReviewTask::getPostId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Post> postMap = postIds.isEmpty() ? new HashMap<>()
                : postMapper.selectBatchIds(postIds).stream()
                        .collect(Collectors.toMap(Post::getId, p -> p));
        // 结论文案:status=1 通过 / 2 驳回 / 0 审核中;驳回时展示可操作理由与处理人
        List<MyReviewVO> vos = page.stream()
                .map(t -> reviewVoAssembler.toMyReviewVO(
                        t, reviewerMap.get(t.getReviewerId()), postMap.get(t.getPostId())))
                .toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public MyReviewVO reviewOfPost(long postId, long viewerId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(POST_NOT_FOUND);
        }
        if (!post.getAuthorId().equals(viewerId)) {
            throw new BusinessException(NOT_POST_AUTHOR, "只能查看自己帖子的处理结果");
        }
        ReviewTask task = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getPostId, postId)
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        if (task != null && Integer.valueOf(2).equals(task.getStatus())) {
            User reviewer = task.getReviewerId() == null ? null : userMapper.selectById(task.getReviewerId());
            return reviewVoAssembler.toMyReviewVO(task, reviewer, post);
        }
        if (!Integer.valueOf(2).equals(post.getStatus())) {
            return null;
        }
        /*
         * 已发布帖被圈主/圈管理直接下架时没有 review_task，理由记在审计留痕里。
         * 旧数据只有「圈管理下架」没有理由，装配器会返回 null 理由。
         */
        AuditLog take = auditLogMapper.selectLatestPostTakedown(postId);
        if (take == null) {
            return null;
        }
        User reviewer = take.getUserId() == null ? null : userMapper.selectById(take.getUserId());
        return reviewVoAssembler.fromTakedown(post, take, reviewer);
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
        List<ReviewTaskVO> vos = reviewVoAssembler.toVos(page);
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
        reviewNotify.pushResult(post.getAuthorId(), 7, post, reviewerId, null);

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
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.isEmpty()) {
            throw new BusinessException(ResultCode.UNPROCESSABLE, "驳回理由不能为空");
        }
        if (safeReason.length() > 512) {
            safeReason = safeReason.substring(0, 512);
        }
        // 1. 占坑:同 approve(审查 P0-7),驳回理由随后单独 UPDATE 该列
        if (reviewTaskMapper.casMarkProcessed(taskId, 2, reviewerId) == 0) {
            throw new BusinessException(ResultCode.UNPROCESSABLE, "该任务已处理");
        }
        reviewTaskMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReviewTask>()
                .eq(ReviewTask::getId, taskId)
                .set(ReviewTask::getReason, safeReason));

        // 2. 帖子状态 0 → 2(下架)。只更新 status 列(审查 P1-14,同 approve)
        postMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, post.getId())
                .set(Post::getStatus, 2)
                .set(Post::getUpdateTime, java.time.LocalDateTime.now()));

        // 3. 审计
        auditService.record(reviewerId, "review_reject", "post", post.getId(),
                "帖子驳回: " + safeReason, null);

        // 4. 通知作者:审核驳回(type=8),理由原文带给作者
        reviewNotify.pushResult(post.getAuthorId(), 8, post, reviewerId, safeReason);

        log.info("review reject: task={}, post={}, reason={}", taskId, task.getPostId(), safeReason);
    }
}
