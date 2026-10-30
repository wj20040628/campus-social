package com.campus.social.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.CircleMember;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.campus.social.common.ResultCode.NOT_CIRCLE_OWNER;

/**
 * 圈内权限判定（2026-10-30 从 CircleAdminServiceImpl 抽出）。
 *
 * <p>权限模型（仿贴吧吧主）：
 * <ul>
 *   <li>吧主级动作（置顶/下架/移帖）需 role∈{1,2}；</li>
 *   <li>任命/取消圈管理、封禁成员需 role=1 圈主（权限止于圈主，不再下放）；</li>
 *   <li>移交/封禁圈子、预留名增删需平台管理员（由调用方 roleService 校验）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CirclePermissionSupport {

    /** 圈内角色:1圈主 2圈管理 3成员 */
    public static final int ROLE_CREATOR = 1;
    public static final int ROLE_ADMIN = 2;
    public static final int ROLE_MEMBER = 3;

    private final CircleMemberMapper circleMemberMapper;

    /** 断言 me 对 circle 有吧主级权限(role∈{1,2})，否则 403。圈主即便 ban_status=1 也不拦。 */
    public void requireCircleAdmin(long circleId, long me) {
        CircleMember row = memberRow(circleId, me);
        int role = row.getRole() == null ? ROLE_MEMBER : row.getRole();
        if (role != ROLE_CREATOR && role != ROLE_ADMIN) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "仅圈主/圈管理可操作");
        }
    }

    /** 断言 me 是 circle 的圈主(role=1)。 */
    public void requireCircleCreator(long circleId, long me) {
        CircleMember row = memberRow(circleId, me);
        int role = row.getRole() == null ? ROLE_MEMBER : row.getRole();
        if (role != ROLE_CREATOR) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "仅圈主可操作");
        }
    }

    public CircleMember memberRow(long circleId, long userId) {
        CircleMember row = circleMemberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId));
        if (row == null) {
            throw new BusinessException(NOT_CIRCLE_OWNER, "你不是该圈成员,无法操作");
        }
        return row;
    }

    /**
     * me 在 circle 是否被圈内封禁（供写路径拦截）。
     * 判定：ban_status=1 且（ban_until 为空=长期，或 ban_until > NOW()）。
     * role=1/2 恒不拦（只作用 role=3）。
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
}
