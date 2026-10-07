package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.entity.Activity;
import com.campus.social.entity.ActivitySignup;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ActivitySignupMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.service.ActivityService;
import com.campus.social.vo.ActivityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final ActivityMapper activityMapper;
    private final ActivitySignupMapper signupMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final com.campus.social.service.AuditService auditService;

    @Override
    @Transactional
    public long create(long creatorId, ActivityDTO dto) {
        // 圈子权限:带 circleId 时必须是圈内成员
        if (dto.getCircleId() != null) {
            Circle c = circleMapper.selectById(dto.getCircleId());
            if (c == null || c.getStatus() == 1) {
                throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
            }
            Long cnt = circleMemberMapper.selectCount(new LambdaQueryWrapper<CircleMember>()
                    .eq(CircleMember::getCircleId, dto.getCircleId())
                    .eq(CircleMember::getUserId, creatorId));
            if (cnt == 0) {
                throw new BusinessException(UNPROCESSABLE, "请先加入该圈子再发布活动");
            }
        }
        Activity a = new Activity();
        a.setTitle(dto.getTitle());
        a.setCircleId(dto.getCircleId());
        a.setCapacity(dto.getCapacity());
        a.setCurrentCount(0);
        a.setStartTime(dto.getStartTime());
        a.setEndTime(dto.getEndTime());
        a.setStatus(0); // 报名中
        activityMapper.insert(a);
        log.info("activity created: id={}, title={}, capacity={}", a.getId(), a.getTitle(), a.getCapacity());
        return a.getId();
    }

    @Override
    public CursorPage<ActivityVO> listByCircle(long circleId, Long lastId, int size, long viewerId) {
        LambdaQueryWrapper<Activity> qw = new LambdaQueryWrapper<Activity>()
                .eq(Activity::getCircleId, circleId)
                .lt(lastId != null, Activity::getId, lastId)
                .orderByDesc(Activity::getId)
                .last("LIMIT " + (size + 1));
        List<Activity> rows = activityMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Activity> page = rows.stream().limit(size).toList();
        List<ActivityVO> vos = page.stream().map(a -> toVo(a, viewerId)).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public ActivityVO detail(long activityId, long viewerId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(NOT_FOUND, "活动不存在");
        }
        return toVo(a, viewerId);
    }

    @Override
    @Transactional
    public void signup(long activityId, long userId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(NOT_FOUND, "活动不存在");
        }
        if (a.getStatus() == 4) {
            throw new BusinessException(ACTIVITY_CANCELLED, "活动已取消");
        }
        if (a.getStatus() == 3) {
            throw new BusinessException(ACTIVITY_OVER, "活动已结束");
        }

        // 幂等:已报名直接返回
        LambdaQueryWrapper<ActivitySignup> exist = new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getActivityId, activityId)
                .eq(ActivitySignup::getUserId, userId);
        if (signupMapper.selectCount(exist) > 0) {
            log.info("signup idempotent skip: {} -> {}", userId, activityId);
            return;
        }

        // 乐观锁防超卖:
        // 1. 先 INSERT 幂等键
        signupMapper.insertIdempotent(activityId, userId);
        // 2. 乐观锁 UPDATE(影响行=0 说明已满或状态变了,回滚 signup)
        int affected = activityMapper.trySignin(activityId);
        if (affected == 0) {
            // 回滚 signup
            signupMapper.delete(exist);
            throw new BusinessException(ACTIVITY_FULL, "活动名额已满或已结束");
        }
        log.info("signup ok: {} -> {}", userId, activityId);
        // 审计:报名
        auditService.record(userId, "signup", "activity", activityId, "活动报名", null);
    }

    @Override
    @Transactional
    public void cancel(long activityId, long userId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null || a.getStatus() == 3 || a.getStatus() == 4) {
            throw new BusinessException(UNPROCESSABLE, "活动已结束或取消,无法退报");
        }
        LambdaQueryWrapper<ActivitySignup> exist = new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getActivityId, activityId)
                .eq(ActivitySignup::getUserId, userId);
        List<ActivitySignup> rows = signupMapper.selectList(exist);
        if (rows.isEmpty()) {
            return; // 未报名,幂等
        }
        // 物理删(绕过 @TableLogic 软删,否则残留行锁住 uk_as,再报名会撞唯一键)
        signupMapper.hardDelete(activityId, userId);
        activityMapper.cancelSignin(activityId);
        // 审计:取消报名
        auditService.record(userId, "cancel_signup", "activity", activityId, "取消报名", null);
    }

    private ActivityVO toVo(Activity a, long viewerId) {
        boolean signedUp = false;
        if (viewerId > 0) {
            LambdaQueryWrapper<ActivitySignup> qw = new LambdaQueryWrapper<ActivitySignup>()
                    .eq(ActivitySignup::getActivityId, a.getId())
                    .eq(ActivitySignup::getUserId, viewerId);
            signedUp = signupMapper.selectCount(qw) > 0;
        }
        boolean canSignup = a.getStatus() == 0 || a.getStatus() == 1;
        return ActivityVO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .circleId(a.getCircleId())
                .capacity(a.getCapacity())
                .currentCount(a.getCurrentCount())
                .startTime(a.getStartTime())
                .endTime(a.getEndTime())
                .status(a.getStatus())
                .createTime(a.getCreateTime())
                .signedUp(signedUp)
                .canSignup(canSignup)
                .build();
    }
}
