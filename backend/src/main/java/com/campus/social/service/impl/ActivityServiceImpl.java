package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.entity.Activity;
import com.campus.social.entity.ActivitySignup;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ActivitySignupMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.ActivityService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.vo.ActivitySignupVO;
import com.campus.social.vo.ActivityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

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
    private final PostNotifySupport postNotifySupport;
    private final UserMapper userMapper;
    /** 行为信任门槛：报名线下活动需账号满 3 天 + 信誉 60 */
    private final CampusAccessService access;

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
        a.setCreatorId(creatorId); // 记录创建者,供报名通知(type=5)推送
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

    /**
     * 报名者名单（2026-10-21 新增）。
     *
     * <p><b>为什么在这里查而不是在 Controller 拼</b>：需要「按报名先后」排序，
     * 而 {@code activity_signup} 的 id 自增恰好等于报名顺序（表里没有 sort 列）。
     * 用 id 升序而不是 create_time 升序：同一秒内批量报名时 create_time 会撞，
     * 而 id 单调唯一 —— 与举报列表用 id 做游标是同一条理由。
     *
     * <p><b>为什么批量查 user</b>：一个活动最多几十人，逐条查会形成 N+1；
     * {@code selectBatchIds} 一次拿回，与 CircleAdminServiceImpl.members 同一做法。
     *
     * <p>不校验 viewer 的权限：报名名单是公开信息（设计稿已确认），
     * 它只包含昵称与头像，不含学号、年级等可用于定位个人的信息。
     */
    @Override
    public List<ActivitySignupVO> signups(long activityId, long viewerId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(NOT_FOUND, "活动不存在");
        }
        List<ActivitySignup> rows = signupMapper.selectList(
                new LambdaQueryWrapper<ActivitySignup>()
                        .eq(ActivitySignup::getActivityId, activityId)
                        .orderByAsc(ActivitySignup::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(ActivitySignup::getUserId).distinct().toList();
        Map<Long, User> byId = new HashMap<>();
        for (User u : userMapper.selectBatchIds(userIds)) {
            byId.put(u.getId(), u);
        }
        return rows.stream().map(r -> {
            User u = byId.get(r.getUserId());
            return ActivitySignupVO.builder()
                    .userId(r.getUserId())
                    // 昵称缺失时退到「匿名」而不是抛错：老数据可能没有昵称
                    .nickname(u != null && u.getNickname() != null ? u.getNickname() : "匿名")
                    .avatarUrl(u != null ? u.getAvatarUrl() : null)
                    .isMe(viewerId != 0 && viewerId == r.getUserId())
                    .build();
        }).toList();
    }

    @Override
    @Transactional
    public void signup(long activityId, long userId) {
        // 线下见面类动作：账号满 3 天 + 信誉 60；校园信息不参与（2026-10-30）
        access.requireActivitySignup(userId);
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
        // 通知创建者:有人报名了他/她创建的活动(type=5)。
        // 旧活动(迁移前创建)creator_id 为 NULL 时跳过,优雅降级不报错。
        if (a.getCreatorId() != null && a.getCreatorId() != userId) {
            postNotifySupport.pushNotify(a.getCreatorId(), 5, activityId, userId, null);
        }
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

    @Override
    @Transactional
    public void cancelActivity(long activityId, long operatorId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(NOT_FOUND, "活动不存在");
        }
        // 仅创建者或管理员可取消整个活动
        boolean isCreator = a.getCreatorId() != null && a.getCreatorId().longValue() == operatorId;
        boolean isAdmin = false;
        if (!isCreator) {
            User op = userMapper.selectById(operatorId);
            isAdmin = op != null && op.getRole() != null && op.getRole() == 1;
        }
        if (!isCreator && !isAdmin) {
            throw new BusinessException(FORBIDDEN, "只有活动创建者或管理员可以取消活动");
        }
        if (a.getStatus() == 4) {
            return; // 已取消,幂等
        }
        a.setStatus(4);
        activityMapper.updateById(a);
        // 通知所有已报名者:你报名的活动已取消(type=12)
        List<ActivitySignup> signups = signupMapper.selectList(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getActivityId, activityId));
        for (ActivitySignup s : signups) {
            if (s.getUserId().equals(operatorId)) continue; // 取消者本人不必收到
            postNotifySupport.pushNotify(s.getUserId(), 12, activityId, operatorId, null);
        }
        // 审计:取消活动
        auditService.record(operatorId, "activity_cancel", "activity", activityId, "取消活动", null);
        log.info("activity cancelled: id={}, operator={}", activityId, operatorId);
    }

    private ActivityVO toVo(Activity a, long viewerId) {
        boolean signedUp = false;
        if (viewerId > 0) {
            LambdaQueryWrapper<ActivitySignup> qw = new LambdaQueryWrapper<ActivitySignup>()
                    .eq(ActivitySignup::getActivityId, a.getId())
                    .eq(ActivitySignup::getUserId, viewerId);
            signedUp = signupMapper.selectCount(qw) > 0;
        }
        /*
         * 报名通道随**结束/取消**关闭,进行中(status 2)仍可报名——
         * 「开始时间=现在」的捡人局创建即开打,若开始即关门,谁也报不了。
         * 这不是产品放宽:signup() 只拦 3/4,trySignin 的乐观锁 SQL 本就允许
         * status IN (0,1,2)——此前读侧标志与写路径语义脱节(2026-10-25 修)。
         */
        boolean canSignup = a.getStatus() != 3 && a.getStatus() != 4;
        return ActivityVO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .circleId(a.getCircleId())
                .creatorId(a.getCreatorId())
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
