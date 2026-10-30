package com.campus.social.service.impl;

import com.campus.social.entity.Circle;
import com.campus.social.entity.Post;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.support.CirclePermissionSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.NOT_CIRCLE_OWNER;
import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.POST_NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 圈内帖子处置（2026-10-30 从 CircleAdminServiceImpl 拆出）：
 * 置顶/取消置顶/下架/移帖，鉴权走 {@link CirclePermissionSupport}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CirclePostModerationService {

    private final CirclePermissionSupport permissions;
    private final PostMapper postMapper;
    private final CircleMapper circleMapper;
    private final AuditService auditService;
    /** 首页首屏缓存:下架/移帖都会改变首页内容,必须失效 */
    private final FeedPageCacheService feedCache;

    public void topPost(long postId, long me) {
        Post p = requirePost(postId);
        if (p.getCircleId() == null) {
            throw new BusinessException(UNPROCESSABLE, "该帖子不在任何圈子,无法置顶");
        }
        permissions.requireCircleAdmin(p.getCircleId(), me);
        // 同圈仅 1 个置顶:先把本圈其它置顶归 0,再置当前为 1
        postMapper.clearTopInCircle(p.getCircleId(), postId);
        postMapper.setIsTop(postId, 1);
        auditService.record(me, "circle_top_post", "post", postId, "圈内置顶", null);
        log.info("circle top post: post={} circle={} admin={}", postId, p.getCircleId(), me);
    }

    public void untopPost(long postId, long me) {
        Post p = requirePost(postId);
        // 广场帖(circleId=null)不在任何圈子的管辖内:取消置顶是圈子管理动作,
        // 无圈可鉴权即一律 403 —— 任何登录用户不能对广场帖执行吧主级操作(2026-10-23 修,审查 P0-2)。
        if (p.getCircleId() == null) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "广场帖子不支持圈子置顶操作");
        }
        permissions.requireCircleAdmin(p.getCircleId(), me);
        postMapper.setIsTop(postId, 0);
        auditService.record(me, "circle_untop_post", "post", postId, "取消置顶", null);
        log.info("circle untop post: post={} admin={}", postId, me);
    }

    public void takedownPost(long postId, long me) {
        Post p = requirePost(postId);
        // 同 untopPost:广场帖无圈可鉴权,之前 if!=null 才鉴权等于对广场帖完全放行 —— 越权(审查 P0-2)。
        if (p.getCircleId() == null) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "广场帖子不在任何圈子,无法执行下架");
        }
        permissions.requireCircleAdmin(p.getCircleId(), me);
        // 下架=软(status=2,作者可自查),不物理删
        postMapper.takedown(postId);
        // 首页缓存失效:下架的内容不该继续出现在任何人的首页上
        feedCache.invalidate();
        auditService.record(me, "circle_takedown_post", "post", postId, "圈管理下架", null);
        log.info("circle takedown post: post={} circle={} admin={}", postId, p.getCircleId(), me);
    }

    public void movePost(long postId, Long toCircleId, long me) {
        Post p = requirePost(postId);
        /*
         * 鉴权规则(2026-10-23 修,审查 P0-3):
         * - 源是圈内帖 → 需源圈管理权(把帖子移走是处置源圈内容)
         * - 源是广场帖 → 允许移入圈子(广场无圈可管,这是把内容归拢进圈的合法运营动作),
         *   但要求操作者对目标圈有管理权 —— 之前 fallback 恒抛 403,广场帖永远无法合法移入。
         * - 目标圈(移入/换圈) → 一律需目标圈管理权:之前只查存在性,A 圈主可把帖子塞进 B 圈污染内容流。
         */
        if (p.getCircleId() != null) {
            permissions.requireCircleAdmin(p.getCircleId(), me);
        }
        if (toCircleId != null && !toCircleId.equals(p.getCircleId())) {
            permissions.requireCircleAdmin(toCircleId, me);
            Circle target = circleMapper.selectById(toCircleId);
            if (target == null || target.getStatus() == 1) {
                throw new BusinessException(NOT_FOUND, "目标圈子不存在或已封禁");
            }
        }
        postMapper.moveCircle(postId, toCircleId);
        auditService.record(me, "circle_move_post", "post", postId,
                "从 " + p.getCircleId() + " 移到 " + toCircleId, null);
        log.info("circle move post: post={} from={} to={} admin={}",
                postId, p.getCircleId(), toCircleId, me);
    }

    private Post requirePost(long postId) {
        Post p = postMapper.selectById(postId);
        if (p == null) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不存在");
        }
        return p;
    }
}
