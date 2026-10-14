package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReservedNameDTO;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReservedCircleName;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReservedCircleNameMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.CircleAdminService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.CircleMemberVO;
import com.campus.social.vo.ReservedNameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.campus.social.common.ResultCode.NOT_CIRCLE_OWNER;
import static com.campus.social.common.ResultCode.*;

/**
 * 圈子「圈管理 / 吧主级权限」实现。
 *
 * <p>权限模型(仿贴吧吧主):
 * <ul>
 *   <li>吧主级动作(置顶/下架/移帖)需 role∈{1,2} —— {@link #requireCircleAdmin}。</li>
 *   <li>任命/取消圈管理、封禁成员需 role=1 圈主 —— {@link #requireCircleCreator}
 *       (权限止于圈主,圈管理不能再下放,防层层传递)。</li>
 *   <li>移交/封禁圈子、预留名增删需平台管理员 —— {@code roleService.requireAdmin}。</li>
 * </ul>
 *
 * <p>封禁(软封禁)只作用在 role=3 普通成员;圈主/圈管理即便 ban_status=1 也不拦
 * (仿 {@code GroupServiceImpl:442} 群主恒可发言的纪律)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircleAdminServiceImpl implements CircleAdminService {

    /** 圈内角色:1圈主 2圈管理 3成员 */
    private static final int ROLE_CREATOR = 1;
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_MEMBER = 3;

    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final ReservedCircleNameMapper reservedNameMapper;
    private final RoleService roleService;
    private final AuditService auditService;

    // ============================================================ 鉴权 helper

    /**
     * 断言 me 对 circle 有吧主级权限(role∈{1,2}),否则 403 NOT_CIRCLE_OWNER(1018)。
     * 圈主即便被误标 ban_status=1 也不拦(与群主恒可发言同纪律)。
     */
    private void requireCircleAdmin(long circleId, long me) {
        CircleMember row = circleMemberRow(circleId, me);
        int role = row.getRole() == null ? ROLE_MEMBER : row.getRole();
        if (role != ROLE_CREATOR && role != ROLE_ADMIN) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "仅圈主/圈管理可操作");
        }
    }

    /** 断言 me 是 circle 的圈主(role=1),用于任命/取消圈管理、封禁成员。 */
    private void requireCircleCreator(long circleId, long me) {
        CircleMember row = circleMemberRow(circleId, me);
        int role = row.getRole() == null ? ROLE_MEMBER : row.getRole();
        if (role != ROLE_CREATOR) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "仅圈主可操作");
        }
    }

    private CircleMember circleMemberRow(long circleId, long userId) {
        CircleMember row = circleMemberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId));
        if (row == null) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "你不是该圈成员,无法操作");
        }
        return row;
    }

    /**
     * me 在 circle 是否被圈内封禁(供写路径拦截)。
     * 判定:ban_status=1 且 (ban_until 为空=长期,或 ban_until > NOW())。
     * role=1/2 恒不拦(只作用 role=3)。
     */
    public boolean isBanned(long circleId, long me) {
        if (circleId <= 0 || me <= 0) {
            return false;
        }
        CircleMember row = circleMemberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, me));
        if (row == null || row.getRole() == null) {
            return false;
        }
        int role = row.getRole();
        if (role != ROLE_MEMBER) {
            return false; // 圈主/圈管理不受封禁拦
        }
        if (row.getBanStatus() == null || row.getBanStatus() != 1) {
            return false;
        }
        // ban_until 为空=长期;否则到点(含边界)即自动解除
        return row.getBanUntil() == null || !row.getBanUntil().isBefore(LocalDateTime.now());
    }

    /** 拦截点统一抛 CIRCLE_MEMBER_BANNED(1055)。 */
    private void assertNotBanned(long circleId, long me) {
        if (isBanned(circleId, me)) {
            throw new BusinessException(CIRCLE_MEMBER_BANNED, "你已被圈主/圈管理封禁,无法在此圈子操作");
        }
    }

    // ============================================================ 帖子处置

    private Post requirePost(long postId) {
        Post p = postMapper.selectById(postId);
        if (p == null) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不存在");
        }
        return p;
    }

    @Override
    @Transactional
    public void topPost(long postId, long me) {
        Post p = requirePost(postId);
        if (p.getCircleId() == null) {
            throw new BusinessException(UNPROCESSABLE, "该帖子不在任何圈子,无法置顶");
        }
        requireCircleAdmin(p.getCircleId(), me);
        // 同圈仅 1 个置顶:先把本圈其它置顶归 0,再置当前为 1
        postMapper.clearTopInCircle(p.getCircleId(), postId);
        postMapper.setIsTop(postId, 1);
        auditService.record(me, "circle_top_post", "post", postId, "圈内置顶", null);
        log.info("circle top post: post={} circle={} admin={}", postId, p.getCircleId(), me);
    }

    @Override
    @Transactional
    public void untopPost(long postId, long me) {
        Post p = requirePost(postId);
        if (p.getCircleId() != null) {
            requireCircleAdmin(p.getCircleId(), me);
        }
        postMapper.setIsTop(postId, 0);
        auditService.record(me, "circle_untop_post", "post", postId, "取消置顶", null);
        log.info("circle untop post: post={} admin={}", postId, me);
    }

    @Override
    @Transactional
    public void takedownPost(long postId, long me) {
        Post p = requirePost(postId);
        if (p.getCircleId() != null) {
            requireCircleAdmin(p.getCircleId(), me);
        }
        // 下架=软(status=2,作者可自查),不物理删
        postMapper.takedown(postId);
        auditService.record(me, "circle_takedown_post", "post", postId, "圈管理下架", null);
        log.info("circle takedown post: post={} circle={} admin={}", postId, p.getCircleId(), me);
    }

    @Override
    @Transactional
    public void movePost(long postId, Long toCircleId, long me) {
        Post p = requirePost(postId);
        requireCircleAdmin(p.getCircleId() == null ? circleOfPostFallback(p) : p.getCircleId(), me);
        // 目标圈存在性校验(toCircleId 非 null 时须为正常圈)
        if (toCircleId != null && !toCircleId.equals(p.getCircleId())) {
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

    /** 帖子已被移出所有圈(circle_id=NULL)时,吧主权限无从谈起——回 403。 */
    private long circleOfPostFallback(Post p) {
        throw new BusinessException(NOT_CIRCLE_OWNER, "该帖子已不在任何圈子,无法操作");
    }

    // ============================================================ 成员处置

    @Override
    @Transactional
    public void banMember(long circleId, long targetUserId, LocalDateTime until, long me) {
        requireCircleCreator(circleId, me);
        if (targetUserId == me) {
            throw new BusinessException(UNPROCESSABLE, "不能封禁自己");
        }
        CircleMember target = circleMemberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? ROLE_MEMBER : target.getRole();
        if (trole == ROLE_CREATOR) {
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

    @Override
    @Transactional
    public void unbanMember(long circleId, long targetUserId, long me) {
        requireCircleCreator(circleId, me);
        int n = circleMemberMapper.setBan(circleId, targetUserId, 0, null);
        if (n == 0) {
            throw new BusinessException(NOT_FOUND, "该成员不在圈内");
        }
        auditService.record(me, "circle_unban_member", "circle", circleId, "解禁成员 " + targetUserId, null);
        log.info("circle unban member: circle={} target={} admin={}", circleId, targetUserId, me);
    }

    @Override
    @Transactional
    public void appointAdmin(long circleId, long targetUserId, long me) {
        requireCircleCreator(circleId, me);
        if (targetUserId == me) {
            throw new BusinessException(UNPROCESSABLE, "不能任命自己");
        }
        CircleMember target = circleMemberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? ROLE_MEMBER : target.getRole();
        if (trole == ROLE_CREATOR) {
            throw new BusinessException(UNPROCESSABLE, "圈主无需任命");
        }
        // 圈管理不能再任命别人:本方法 requireCircleCreator 已保证只有圈主(role=1)能进来
        circleMemberMapper.setRole(circleId, targetUserId, ROLE_ADMIN);
        auditService.record(me, "circle_appoint_admin", "circle", circleId, "任命圈管理 " + targetUserId, null);
        log.info("circle appoint admin: circle={} target={} by={}", circleId, targetUserId, me);
    }

    @Override
    @Transactional
    public void revokeAdmin(long circleId, long targetUserId, long me) {
        requireCircleCreator(circleId, me);
        CircleMember target = circleMemberRow(circleId, targetUserId);
        int trole = target.getRole() == null ? ROLE_MEMBER : target.getRole();
        if (trole != ROLE_ADMIN) {
            throw new BusinessException(UNPROCESSABLE, "该成员不是圈管理");
        }
        circleMemberMapper.setRole(circleId, targetUserId, ROLE_MEMBER);
        auditService.record(me, "circle_revoke_admin", "circle", circleId, "取消圈管理 " + targetUserId, null);
        log.info("circle revoke admin: circle={} target={} by={}", circleId, targetUserId, me);
    }

    @Override
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
                    .role(m.getRole() == null ? ROLE_MEMBER : m.getRole())
                    .banStatus(banNow)
                    .banUntil(banNow == 1 ? m.getBanUntil() : null)
                    .joinTime(m.getCreateTime())
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

    // ============================================================ 管理员:官方名处置

    @Override
    @Transactional
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
        CircleMember newOwnerRow = circleMemberRow(circleId, newOwnerId);
        long oldOwnerId = c.getOwnerId();
        circleMapper.transferOwner(circleId, newOwnerId);
        // 角色翻转:原圈主 1→3,新圈主→1
        circleMemberMapper.setRole(circleId, oldOwnerId, ROLE_MEMBER);
        circleMemberMapper.setRole(circleId, newOwnerId, ROLE_CREATOR);
        auditService.record(adminId, "circle_transfer_owner", "circle", circleId,
                "移交圈主 " + oldOwnerId + " → " + newOwnerId, null);
        log.info("circle transfer owner: circle={} from={} to={} admin={}",
                circleId, oldOwnerId, newOwnerId, adminId);
    }

    @Override
    @Transactional
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

    @Override
    @Transactional
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

    // ============================================================ 预留名(运营后台)

    @Override
    @Transactional
    public void addReservedName(ReservedNameDTO dto, long adminId) {
        roleService.requireAdmin(adminId);
        String name = dto.getName().trim();
        if (name.length() < 1 || name.length() > 64) {
            throw new BusinessException(BAD_REQUEST, "圈子名长度需在 1-64 字符");
        }
        Long cnt = reservedNameMapper.selectCount(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name));
        if (cnt != 0) {
            throw new BusinessException(CONFLICT, "该圈子名已在预留表");
        }
        ReservedCircleName rn = new ReservedCircleName();
        rn.setName(name);
        rn.setNote(dto.getNote());
        reservedNameMapper.insert(rn);
        auditService.record(adminId, "reserved_name_add", "reserved_circle_name", rn.getId(),
                "预留圈名 " + name, null);
        log.info("reserved name added: {} by admin={}", name, adminId);
    }

    @Override
    @Transactional
    public void removeReservedName(String name, long adminId) {
        roleService.requireAdmin(adminId);
        ReservedCircleName rn = reservedNameMapper.selectOne(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name));
        if (rn == null) {
            throw new BusinessException(NOT_FOUND, "预留名不存在");
        }
        reservedNameMapper.deleteById(rn.getId());
        auditService.record(adminId, "reserved_name_remove", "reserved_circle_name", rn.getId(),
                "移除预留圈名 " + name, null);
        log.info("reserved name removed: {} by admin={}", name, adminId);
    }

    @Override
    public CursorPage<ReservedNameVO> listReservedNames(Long lastId, int size, long adminId) {
        roleService.requireAdmin(adminId);
        LambdaQueryWrapper<ReservedCircleName> qw = new LambdaQueryWrapper<ReservedCircleName>()
                .lt(lastId != null, ReservedCircleName::getId, lastId)
                .orderByDesc(ReservedCircleName::getId)
                .last("LIMIT " + (size + 1));
        List<ReservedCircleName> rows = reservedNameMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<ReservedNameVO> page = rows.stream().limit(size)
                .map(r -> ReservedNameVO.builder()
                        .id(r.getId()).name(r.getName()).note(r.getNote()).createTime(r.getCreateTime())
                        .build())
                .toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(page, nextCursor, hasMore);
    }

    @Override
    public boolean isReservedName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Long cnt = reservedNameMapper.selectCount(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name.trim()));
        return cnt != 0;
    }
}
