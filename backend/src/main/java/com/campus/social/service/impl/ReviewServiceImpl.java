package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.NotifyService;
import com.campus.social.util.SensitiveWordFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.social.service.PostService;
import com.campus.social.service.ReviewService;
import com.campus.social.vo.ReviewTaskVO;
import com.campus.social.vo.UserVO;
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
    private final PostService postService;
    private final NotifyService notifyService;
    private final NotifyMapper notifyMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;
    private final AuditService auditService;
    private final ObjectMapper om;
    private final SensitiveWordFilter sensitive;

    @Override
    public CursorPage<ReviewTaskVO> pending(long reviewerId, Long lastId, int size) {
        return listByStatus(0, lastId, size);
    }

    @Override
    public CursorPage<ReviewTaskVO> processed(long reviewerId, Long lastId, int size) {
        return listByStatus(null, lastId, size);
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

    private CursorPage<ReviewTaskVO> listByStatus(Integer status, Long lastId, int size) {
        LambdaQueryWrapper<ReviewTask> qw = new LambdaQueryWrapper<ReviewTask>()
                .eq(status != null, ReviewTask::getStatus, status)
                .lt(lastId != null, ReviewTask::getId, lastId)
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT " + (size + 1));
        List<ReviewTask> rows = reviewTaskMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<ReviewTask> page = rows.stream().limit(size).toList();

        List<ReviewTaskVO> vos = page.stream().map(this::toVo).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
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

    private ReviewTaskVO toVo(ReviewTask task) {
        Post post = postMapper.selectById(task.getPostId());
        UserVO avo = UserVoAssembler.of(post == null ? null : userMapper.selectById(post.getAuthorId()));
        return ReviewTaskVO.builder()
                .id(task.getId())
                .postId(task.getPostId())
                .content(post != null ? post.getContent() : "(帖子已删)")
                .media(post != null ? post.getMedia() : null)
                .author(avo)
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
