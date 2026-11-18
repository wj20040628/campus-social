package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.entity.User;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.vo.MyReviewVO;
import com.campus.social.vo.ReviewTaskVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审核任务与审核记录 → VO 的唯一装配出口（2026-11-14 从 ReviewServiceImpl 拆出）。
 *
 * <p>待审队列、已处理列表、作者侧的「我的审核记录」三处展示共用同一套规则：
 * 处理人身份的匿名口径（平台管理员与 AI 不暴露昵称，圈主/圈管理保留昵称）、
 * 驳回理由与命中词解析、圈内下架理由的回填。规则散在列表与详情两处必然漂移，
 * 所以集中在这里；ReviewServiceImpl 只保留队列、权限与处置流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewVoAssembler {

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CircleMapper circleMapper;
    private final ObjectMapper om;

    /**
     * 批量组装待审任务（2026-10-24 改）。
     *
     * <p>原逐条两次单查（post + author），一页 20 条 = 40 次查询。
     * 这里用两次 IN 查询换掉 2N 次单查，圈名再补一次 IN。
     */
    public List<ReviewTaskVO> toVos(List<ReviewTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> posts = postMapper.selectBatchIds(
                        tasks.stream().map(ReviewTask::getPostId).distinct().toList())
                .stream().collect(Collectors.toMap(Post::getId, p -> p));
        Set<Long> authorIds = posts.values().stream()
                .map(Post::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = authorIds.isEmpty() ? new HashMap<>()
                : userMapper.selectBatchIds(authorIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));
        Set<Long> circleIds = posts.values().stream()
                .map(Post::getCircleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Circle> circles = circleIds.isEmpty() ? new HashMap<>()
                : circleMapper.selectBatchIds(circleIds).stream()
                        .collect(Collectors.toMap(Circle::getId, c -> c));
        return tasks.stream()
                .map(t -> buildVo(t, posts.get(t.getPostId()),
                        posts.get(t.getPostId()) == null ? null
                                : users.get(posts.get(t.getPostId()).getAuthorId()),
                        posts.get(t.getPostId()) == null || posts.get(t.getPostId()).getCircleId() == null
                                ? null : circles.get(posts.get(t.getPostId()).getCircleId())))
                .toList();
    }

    private ReviewTaskVO buildVo(ReviewTask task, Post post, User author, Circle circle) {
        return ReviewTaskVO.builder()
                .id(task.getId())
                .postId(task.getPostId())
                .circleId(post != null ? post.getCircleId() : null)
                .circleName(circle != null ? circle.getName() : null)
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

    /** 作者侧「我的审核记录」：按任务状态回填结论、处理人与时间 */
    public MyReviewVO toMyReviewVO(ReviewTask task, User reviewer, Post post) {
        ReviewerIdentity id = reviewerIdentity(reviewer, post, task);
        LocalDateTime reviewedAt = null;
        if (task.getStatus() != null && task.getStatus() != 0) {
            reviewedAt = task.getReviewerId() == null && task.getAiReviewedAt() != null
                    ? task.getAiReviewedAt() : task.getUpdateTime();
        }
        return MyReviewVO.builder()
                .postId(task.getPostId())
                .status(task.getStatus())
                .hitWords(parseHitWords(task.getHitWords()))
                .reason(task.getReason())
                .aiVerdict(task.getAiVerdict())
                .aiReason(task.getAiReason())
                .reviewerId(task.getReviewerId())
                .reviewerName(id.name())
                .reviewerRole(id.role())
                .reviewedAt(reviewedAt)
                .createTime(task.getCreateTime())
                .build();
    }

    /**
     * 已发布帖被圈主/圈管理直接下架时没有 review_task，理由与处理人记在审计留痕里。
     * 旧数据只有「圈管理下架」没有理由，extractTakedownReason 会返回 null。
     */
    public MyReviewVO fromTakedown(Post post, AuditLog take, User reviewer) {
        ReviewerIdentity id = takedownIdentity(reviewer, post);
        return MyReviewVO.builder()
                .postId(post.getId())
                .status(2)
                .hitWords(List.of())
                .reason(extractTakedownReason(take.getDetail()))
                .reviewerId(take.getUserId())
                .reviewerName(id.name())
                .reviewerRole(id.role())
                .reviewedAt(take.getCreateTime())
                .createTime(take.getCreateTime())
                .build();
    }

    /**
     * 处理人展示口径：平台管理员与 AI 不暴露个人昵称；圈主/圈管理保留昵称。
     * 只有在能拿到 post 时才区分圈主与圈管理，列表场景退化为一句话角色。
     */
    private ReviewerIdentity reviewerIdentity(User reviewer, Post post, ReviewTask task) {
        if (reviewer == null) {
            if (task != null && task.getStatus() != null && task.getStatus() != 0) {
                return task.getAiVerdict() != null
                        ? new ReviewerIdentity("AI 机审", null)
                        : new ReviewerIdentity("系统", null);
            }
            return new ReviewerIdentity(null, null);
        }
        String name = reviewer.getNickname() == null || reviewer.getNickname().isBlank()
                ? "圈务人员" : reviewer.getNickname();
        // 圈主身份优先于平台管理员：圈主昵称本就对圈成员公开，作者要能对上「谁处理的」
        if (post != null && post.getCircleId() != null) {
            Circle c = circleMapper.selectById(post.getCircleId());
            if (c != null && reviewer.getId().equals(c.getOwnerId())) {
                return new ReviewerIdentity(name, "圈主");
            }
        }
        if (Integer.valueOf(1).equals(reviewer.getRole())) {
            return new ReviewerIdentity("平台管理员", null);
        }
        if (post != null && post.getCircleId() != null) {
            return new ReviewerIdentity(name, "圈管理");
        }
        return new ReviewerIdentity(name, "圈主/圈管理");
    }

    /**
     * 圈内下架的身份口径：这是圈务动作，即便操作人同时是平台管理员，
     * 也应显示其圈内身份（圈主/圈管理）与昵称 —— 作者要能对上「谁下的」。
     */
    private ReviewerIdentity takedownIdentity(User reviewer, Post post) {
        if (reviewer == null) {
            return new ReviewerIdentity("系统", null);
        }
        String name = reviewer.getNickname() == null || reviewer.getNickname().isBlank()
                ? "圈务人员" : reviewer.getNickname();
        if (post != null && post.getCircleId() != null) {
            Circle c = circleMapper.selectById(post.getCircleId());
            if (c != null && reviewer.getId().equals(c.getOwnerId())) {
                return new ReviewerIdentity(name, "圈主");
            }
            return new ReviewerIdentity(name, "圈管理");
        }
        return new ReviewerIdentity(name, "圈务人员");
    }

    private String extractTakedownReason(String detail) {
        if (detail == null) {
            return null;
        }
        int i = detail.indexOf('：');
        if (i < 0 || i + 1 >= detail.length()) {
            return null;
        }
        String reason = detail.substring(i + 1).trim();
        return reason.isEmpty() ? null : reason;
    }

    /** 解析 review_task.hit_words(Java List.toString 形式 [a, b] 或 JSON)为 List<String> */
    private List<String> parseHitWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            String s = raw.trim();
            if (s.startsWith("[")) {
                return om.readValue(s, new TypeReference<List<String>>() {});
            }
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

    private record ReviewerIdentity(String name, String role) {
    }
}