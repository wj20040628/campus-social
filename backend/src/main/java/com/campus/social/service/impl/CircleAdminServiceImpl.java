package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.ReservedNameDTO;
import com.campus.social.entity.Circle;
import com.campus.social.entity.User;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.CircleAdminService;
import com.campus.social.service.RoleService;
import com.campus.social.service.support.CirclePermissionSupport;
import com.campus.social.vo.CircleMemberVO;
import com.campus.social.vo.ReservedNameVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 圈子「圈管理 / 吧主级权限」门面（2026-10-30 拆分）。
 *
 * <p>帖子处置 → {@link CirclePostModerationService}，成员管理 →
 * {@link CircleMemberAdminService}，生命周期 → {@link CircleLifecycleAdminService}，
 * 官方名 → {@link ReservedNameAdminService}；权限判定统一走 {@link CirclePermissionSupport}。
 * 事务边界留在门面，写方法仍是一个事务。
 */
@Service
@RequiredArgsConstructor
public class CircleAdminServiceImpl implements CircleAdminService {

    private final CirclePostModerationService postModeration;
    private final CircleMemberAdminService memberAdmin;
    private final CircleLifecycleAdminService lifecycle;
    private final ReservedNameAdminService reservedNames;
    private final CirclePermissionSupport permissions;
    private final CircleMapper circleMapper;
    private final UserMapper userMapper;
    private final RoleService roleService;

    // ============================================================ 帖子处置

    @Override
    @Transactional
    public void topPost(long postId, long me) {
        postModeration.topPost(postId, me);
    }

    @Override
    @Transactional
    public void untopPost(long postId, long me) {
        postModeration.untopPost(postId, me);
    }

    @Override
    @Transactional
    public void takedownPost(long postId, long me) {
        postModeration.takedownPost(postId, me);
    }

    @Override
    @Transactional
    public void movePost(long postId, Long toCircleId, long me) {
        postModeration.movePost(postId, toCircleId, me);
    }

    // ============================================================ 成员处置

    @Override
    @Transactional
    public void banMember(long circleId, long targetUserId, LocalDateTime until, long me) {
        memberAdmin.banMember(circleId, targetUserId, until, me);
    }

    @Override
    @Transactional
    public void unbanMember(long circleId, long targetUserId, long me) {
        memberAdmin.unbanMember(circleId, targetUserId, me);
    }

    @Override
    @Transactional
    public void appointAdmin(long circleId, long targetUserId, long me) {
        memberAdmin.appointAdmin(circleId, targetUserId, me);
    }

    @Override
    @Transactional
    public void revokeAdmin(long circleId, long targetUserId, long me) {
        memberAdmin.revokeAdmin(circleId, targetUserId, me);
    }

    @Override
    public List<CircleMemberVO> members(long circleId, long viewerId) {
        return memberAdmin.members(circleId, viewerId);
    }

    @Override
    public boolean isBanned(long circleId, long me) {
        return permissions.isBanned(circleId, me);
    }

    // ============================================================ 生命周期

    @Override
    @Transactional
    public void transferCircle(long circleId, long newOwnerId, long adminId) {
        lifecycle.transferCircle(circleId, newOwnerId, adminId);
    }

    @Override
    @Transactional
    public void banCircle(long circleId, long adminId) {
        lifecycle.banCircle(circleId, adminId);
    }

    @Override
    @Transactional
    public void unbanCircle(long circleId, long adminId) {
        lifecycle.unbanCircle(circleId, adminId);
    }

    @Override
    @Transactional
    public void disbandCircle(long circleId, long operatorId) {
        lifecycle.disbandCircle(circleId, operatorId);
    }

    @Override
    @Transactional
    public void restoreCircle(long circleId, long adminId) {
        lifecycle.restoreCircle(circleId, adminId);
    }

    // ============================================================ 官方名

    @Override
    @Transactional
    public void addReservedName(ReservedNameDTO dto, long adminId) {
        reservedNames.addReservedName(dto, adminId);
    }

    @Override
    @Transactional
    public void removeReservedName(String name, long adminId) {
        reservedNames.removeReservedName(name, adminId);
    }

    @Override
    public CursorPage<ReservedNameVO> listReservedNames(Long lastId, int size, long adminId) {
        return reservedNames.listReservedNames(lastId, size, adminId);
    }

    @Override
    public boolean isReservedName(String name) {
        return reservedNames.isReservedName(name);
    }

    // ============================================================ 管理员列表

    /**
     * 管理员处置列表:查全量圈子(含 status=1 封禁/解散),按 id 倒序游标分页。
     * 实现里不重实现 toVo —— 委托 CircleService.list(全量版)由 CircleServiceImpl
     * 负责装配,避免两处 CircleVO 字段口径漂移。
     */
    @Override
    public CursorPage<com.campus.social.vo.CircleVO> listForAdmin(Long lastId, int size, long adminId) {
        roleService.requireAdmin(adminId);
        LambdaQueryWrapper<Circle> qw = new LambdaQueryWrapper<Circle>()
                .lt(lastId != null, Circle::getId, lastId)
                .orderByDesc(Circle::getId)
                .last("LIMIT " + (size + 1));
        List<Circle> rows = circleMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Circle> page = rows.stream().limit(size).toList();
        List<com.campus.social.vo.CircleVO> vos = page.stream()
                .map(this::toVoForAdmin)
                .toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /** 管理后台专用 CircleVO 装配:不查 viewer 的 membership(joined/myRole/myBanned 全置默认),
     *  因为管理后台关心的是圈子本身的状态,不是「我这个管理员加没加」。 */
    private com.campus.social.vo.CircleVO toVoForAdmin(Circle c) {
        User owner = userMapper.selectById(c.getOwnerId());
        com.campus.social.vo.UserVO ovo = owner != null ? UserVoAssembler.of(owner) : null;
        return com.campus.social.vo.CircleVO.builder()
                .id(c.getId())
                .name(c.getName())
                .ownerId(c.getOwnerId())
                .owner(ovo)
                .memberCount(c.getMemberCount())
                .status(c.getStatus())
                .createTime(c.getCreateTime())
                .joined(false)
                .myRole(0)
                .myBanned(false)
                .build();
    }
}
