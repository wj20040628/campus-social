package com.campus.social.service.impl;

import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.RoleService;
import com.campus.social.service.support.CirclePermissionSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 圈子生命周期（2026-10-30 从 CircleAdminServiceImpl 拆出）：
 * 移交圈主、平台封禁/解封、圈主解散、平台恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircleLifecycleAdminService {

    private final CirclePermissionSupport permissions;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final PostMapper postMapper;
    private final RoleService roleService;
    private final AuditService auditService;
    /** 首页首屏缓存:解散/恢复都会改变首页内容,必须失效 */
    private final FeedPageCacheService feedCache;

    public void transferCircle(long circleId, long newOwnerId, long adminId) {
        roleService.requireAdmin(adminId);
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
        }
        if (c.getOwnerId().equals(newOwnerId)) {
            return; // 已是目标圈主,幂等
        }
        // 新圈主须为圈内成员
        CircleMember newOwnerRow = permissions.memberRow(circleId, newOwnerId);
        long oldOwnerId = c.getOwnerId();
        circleMapper.transferOwner(circleId, newOwnerId);
        // 角色翻转:原圈主 1→3,新圈主→1
        circleMemberMapper.setRole(circleId, oldOwnerId, CirclePermissionSupport.ROLE_MEMBER);
        circleMemberMapper.setRole(circleId, newOwnerId, CirclePermissionSupport.ROLE_CREATOR);
        auditService.record(adminId, "circle_transfer_owner", "circle", circleId,
                "移交圈主 " + oldOwnerId + " → " + newOwnerId, null);
        log.info("circle transfer owner: circle={} from={} to={} admin={}",
                circleId, oldOwnerId, newOwnerId, adminId);
    }

    public void banCircle(long circleId, long adminId) {
        roleService.requireAdmin(adminId);
        Circle c = circleMapper.selectById(circleId);
        if (c == null) {
            throw new BusinessException(NOT_FOUND, "圈子不存在");
        }
        circleMapper.setStatus(circleId, 1);
        auditService.record(adminId, "circle_ban", "circle", circleId, "封禁圈子", null);
        log.info("circle banned by admin: circle={} admin={}", circleId, adminId);
    }

    public void unbanCircle(long circleId, long adminId) {
        roleService.requireAdmin(adminId);
        Circle c = circleMapper.selectById(circleId);
        if (c == null) {
            throw new BusinessException(NOT_FOUND, "圈子不存在");
        }
        circleMapper.setStatus(circleId, 0);
        auditService.record(adminId, "circle_unban", "circle", circleId, "解封圈子", null);
        log.info("circle unbanned by admin: circle={} admin={}", circleId, adminId);
    }

    public void disbandCircle(long circleId, long operatorId) {
        // 解散是圈主级动作:仅 role=1 圈主可操作(requireCircleCreator),
        // 区别于 banCircle 走 roleService.requireAdmin(平台管理员)。
        permissions.requireCircleCreator(circleId, operatorId);
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已解散");
        }
        // 1) 圈子软删:status=1 后 list/detail/推荐/搜索/活动按 status 全过滤,自动消失
        circleMapper.disband(circleId);
        // 2) 级联软删圈内帖子:status=2(已删),从广场 Feed/话题/热门一并隐藏,
        //    残留帖不再露出。帖子数据保留(未物理删),管理员可审计。
        postMapper.invalidateCirclePosts(circleId);
        // 首页缓存失效:整个圈的帖子刚被批量下架,首页缓存里可能全是它们
        feedCache.invalidate();
        auditService.record(operatorId, "circle_disband", "circle", circleId, "解散圈子", null);
        log.info("circle disbanded by owner: circle={} operator={}", circleId, operatorId);
    }

    public void restoreCircle(long circleId, long adminId) {
        // 思路 B 兜底:圈主侧不给恢复入口(解散文案承诺「不可恢复」),
        // 恢复只走平台管理员通道,保留「圈主解散=终态」的强语义。
        roleService.requireAdmin(adminId);
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() != 1) {
            // 只有 status=1(封禁/解散)的圈才可恢复;正常圈调它没意义
            throw new BusinessException(UNPROCESSABLE, "该圈子不是封禁/解散状态,无需恢复");
        }
        // 1) 圈子 status 1→0
        circleMapper.setStatus(circleId, 0);
        // 2) 圈内帖子 status 2→1(对称 invalidateCirclePosts 的级联)
        postMapper.restoreCirclePosts(circleId);
        // 首页缓存失效:恢复后圈内帖子应立刻可见,而不是等 60 秒自然过期
        feedCache.invalidate();
        auditService.record(adminId, "circle_restore", "circle", circleId, "恢复圈子", null);
        log.info("circle restored by admin: circle={} admin={}", circleId, adminId);
    }
}
