package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.support.CirclePermissionSupport;
import com.campus.social.vo.CircleMemberVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 圈内成员管理（2026-10-30 从 CircleAdminServiceImpl 拆出）：
 * 封禁/解禁、任命/取消圈管理、成员列表。
 *
 * <p>封禁(软封禁)只作用在 role=3 普通成员;圈主/圈管理即便 ban_status=1 也不拦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircleMemberAdminService {

    private final CirclePermissionSupport permissions;
    private final CircleMemberMapper circleMemberMapper;
    private final CircleMapper circleMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public void banMember(long circleId, long targetUserId, LocalDateTime until, long me) {
        permissions.requireCircleCreator(circleId, me);
        if (targetUserId == me) {
            throw new BusinessException(UNPROCESSABLE, "不能封禁自己");
        }
        CircleMember target = permissions.memberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? CirclePermissionSupport.ROLE_MEMBER : target.getRole();
        if (trole == CirclePermissionSupport.ROLE_CREATOR) {
            throw new BusinessException(UNPROCESSABLE, "不能封禁圈主");
        }
        // until 必须非过去(过去时间等价于未封禁,拦掉防误传)
        if (until != null && until.isBefore(LocalDateTime.now())) {
            throw new BusinessException(UNPROCESSABLE, "封禁到期时间需晚于当前");
        }
        circleMemberMapper.setBan(circleId, targetUserId, 1, until);
        auditService.record(me, "circle_ban_member", "circle", circleId,
                "封禁成员 " + targetUserId + (until != null ? " 至 " + until : " 长期"), null);
        log.info("circle ban member: circle={} target={} until={} admin={}",
                circleId, targetUserId, until, me);
    }

    public void unbanMember(long circleId, long targetUserId, long me) {
        permissions.requireCircleCreator(circleId, me);
        int n = circleMemberMapper.setBan(circleId, targetUserId, 0, null);
        if (n == 0) {
            throw new BusinessException(NOT_FOUND, "该成员不在圈内");
        }
        auditService.record(me, "circle_unban_member", "circle", circleId, "解禁成员 " + targetUserId, null);
        log.info("circle unban member: circle={} target={} admin={}", circleId, targetUserId, me);
    }

    public void appointAdmin(long circleId, long targetUserId, long me) {
        permissions.requireCircleCreator(circleId, me);
        if (targetUserId == me) {
            throw new BusinessException(UNPROCESSABLE, "不能任命自己");
        }
        CircleMember target = permissions.memberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? CirclePermissionSupport.ROLE_MEMBER : target.getRole();
        if (trole == CirclePermissionSupport.ROLE_CREATOR) {
            throw new BusinessException(UNPROCESSABLE, "圈主无需任命");
        }
        // 圈管理不能再任命别人:本方法 requireCircleCreator 已保证只有圈主(role=1)能进来
        circleMemberMapper.setRole(circleId, targetUserId, CirclePermissionSupport.ROLE_ADMIN);
        auditService.record(me, "circle_appoint_admin", "circle", circleId, "任命圈管理 " + targetUserId, null);
        log.info("circle appoint admin: circle={} target={} by={}", circleId, targetUserId, me);
    }

    public void revokeAdmin(long circleId, long targetUserId, long me) {
        permissions.requireCircleCreator(circleId, me);
        CircleMember target = permissions.memberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? CirclePermissionSupport.ROLE_MEMBER : target.getRole();
        if (trole != CirclePermissionSupport.ROLE_ADMIN) {
            throw new BusinessException(UNPROCESSABLE, "该成员不是圈管理");
        }
        circleMemberMapper.setRole(circleId, targetUserId, CirclePermissionSupport.ROLE_MEMBER);
        auditService.record(me, "circle_revoke_admin", "circle", circleId, "取消圈管理 " + targetUserId, null);
        log.info("circle revoke admin: circle={} target={} by={}", circleId, targetUserId, me);
    }

    public List<CircleMemberVO> members(long circleId, long viewerId) {
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
        }
        List<CircleMember> rows = circleMemberMapper.selectList(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .orderByAsc(CircleMember::getRole)
                .orderByDesc(CircleMember::getId));
        // 批量取昵称/头像(避免 N+1)
        List<Long> userIds = rows.stream().map(CircleMember::getUserId).distinct().toList();
        List<User> users = userIds.isEmpty() ? List.of() : userMapper.selectBatchIds(userIds);
        java.util.Map<Long, User> byId = new java.util.HashMap<>();
        for (User u : users) {
            byId.put(u.getId(), u);
        }
        return rows.stream().map(m -> {
            User u = byId.get(m.getUserId());
            int banNow = currentBanStatus(m);
            return CircleMemberVO.builder()
                    .userId(m.getUserId())
                    .nickname(u != null ? u.getNickname() : (u != null ? u.getUsername() : "未知"))
                    .avatarUrl(u != null ? u.getAvatarUrl() : null)
                    .role(m.getRole() == null ? CirclePermissionSupport.ROLE_MEMBER : m.getRole())
                    .banStatus(banNow)
                    .banUntil(banNow == 1 ? m.getBanUntil() : null)
                    .joinTime(m.getCreateTime())
                    /*
                     * 年级/学院（2026-10-21）：成员页要显示「2023 级 · 人工智能学院」。
                     * user 表里这两个是字典化之前就有的名称字符串，直接取用。
                     * 老用户可能为 null（字典化前没填），前端会跳过这一段而不是显示「null」。
                     */
                    .grade(u != null ? u.getGrade() : null)
                    .dept(u != null ? u.getDept() : null)
                    .build();
        }).toList();
    }

    /** 行内 ban_status 到点动态重算:ban_until 非空且已过期 → 视为 0(自动解禁,不落库) */
    private int currentBanStatus(CircleMember m) {
        if (m.getBanStatus() == null || m.getBanStatus() != 1) {
            return 0;
        }
        if (m.getBanUntil() != null && m.getBanUntil().isBefore(LocalDateTime.now())) {
            return 0;
        }
        return 1;
    }
}
