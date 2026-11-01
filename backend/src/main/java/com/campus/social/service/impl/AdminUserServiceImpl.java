package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FeedbackMapper;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AdminUserService;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.UserMuteService;
import com.campus.social.vo.PendingSummaryVO;
import com.campus.social.vo.UserAdminItemVO;
import com.campus.social.vo.UserAdminPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 管理员用户处置实现。设计说明见 {@link com.campus.social.service.AdminUserService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserMapper userMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReportMapper reportMapper;
    private final FeedbackMapper feedbackMapper;
    private final FileAssetMapper fileAssetMapper;
    private final BanService banService;
    private final UserMuteService muteService;
    private final AuditService auditService;
    /** 封禁/解封通知（2026-10-28）：用户不再只看到「账号已冻结」四个字 */
    private final PostNotifySupport notifySupport;

    /** notify.type：账号被封禁 / 账号已解封 / 账号被禁言 / 禁言解除（与 ReportNoticeService 同编号） */
    private static final int NOTIFY_ACCOUNT_BANNED = 19;
    private static final int NOTIFY_ACCOUNT_UNBANNED = 21;
    private static final int NOTIFY_ACCOUNT_MUTED = 24;
    private static final int NOTIFY_ACCOUNT_UNMUTED = 25;

    /** 禁言通知里的时间格式，与 ReportNoticeService 一致 */
    private static final java.time.format.DateTimeFormatter MUTE_TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 圈主协审升级窗口(小时),与 ReviewServiceImpl 读同一个配置键 */
    @org.springframework.beans.factory.annotation.Value("${campus.review.owner-escalate-hours:24}")
    private int ownerEscalateHours;

    @Override
    public UserAdminPageVO page(String keyword, Integer status, Boolean muted, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<User> rows = userMapper.selectList(baseQuery(keyword, status, muted)
                .lt(cursor != null, User::getId, cursor)
                .orderByDesc(User::getId)
                .last("LIMIT " + (limit + 1)));
        boolean hasMore = rows.size() > limit;
        List<User> page = hasMore ? rows.subList(0, limit) : rows;

        UserAdminPageVO vo = new UserAdminPageVO();
        vo.setList(page.stream().map(this::toItem).toList());
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        // total 是当前筛选的全量数:「搜出 3 个叫张三的人」与「本页只有 20 条」是两件事
        vo.setTotal(userMapper.selectCount(baseQuery(keyword, status, muted)));
        return vo;
    }

    /** 筛选条件单独成方法:分页查询与 total 计数必须是同一套 WHERE,否则两数对不上 */
    private LambdaQueryWrapper<User> baseQuery(String keyword, Integer status, Boolean muted) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(status != null, User::getStatus, status)
                // 禁言中的前提是账号正常(0)；封禁中即使 mute_until 未清也不算禁言
                .eq(Boolean.TRUE.equals(muted), User::getStatus, 0)
                // 禁言中 = 截止时间仍在未来;过期的 mute_until 不清理,靠时间比较自然失效
                .gt(Boolean.TRUE.equals(muted), User::getMuteUntil, java.time.LocalDateTime.now());
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(User::getNickname, kw)
                    .or().like(User::getUsername, kw)
                    .or().like(User::getStudentNo, kw));
        }
        return qw;
    }

    private UserAdminItemVO toItem(User u) {
        UserAdminItemVO item = new UserAdminItemVO();
        item.setUserId(u.getId());
        item.setNickname(u.getNickname());
        item.setUsername(UserVoAssembler.maskUsername(u.getUsername()));
        item.setAvatarUrl(u.getAvatarUrl());
        item.setStudentNo(u.getStudentNo());
        item.setDept(u.getDept());
        item.setGrade(u.getGrade());
        item.setVerifyStatus(u.getVerifyStatus());
        item.setRole(u.getRole());
        item.setStatus(u.getStatus());
        item.setCreateTime(u.getCreateTime());
        // 只有冻结行才值得查一次 Redis 解封计划;正常行必然 null
        item.setBannedUntil(Integer.valueOf(1).equals(u.getStatus())
                ? banService.bannedUntil(u.getId()) : null);
        item.setMuteUntil(u.getMuteUntil());
        return item;
    }

    @Override
    @Transactional
    public void ban(long adminId, long userId, int banDays, String reason) {
        if (adminId == userId) {
            throw new BusinessException(UNPROCESSABLE, "不能封禁自己");
        }
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(1).equals(target.getRole())) {
            /*
             * 封掉管理员 = 可能把后台锁死(单管理员运营期是必然,无人能再解封),
             * 且管理员账号失窃的正确处置是改密而非封禁。
             */
            throw new BusinessException(UNPROCESSABLE, "不能封禁管理员账号");
        }
        if (Integer.valueOf(2).equals(target.getStatus())) {
            /*
             * 注销是不可逆的一次性删除；若允许封禁，status 会被改成 1，
             * 到期解封再改回 0——已注销账号会被「复活」成正常账号。
             */
            throw new BusinessException(UNPROCESSABLE, "该账号已注销，无法封禁");
        }
        banService.ban(userId, banDays);
        auditService.record(adminId, "account_ban", "user", userId,
                (banDays == 0 ? "直接封禁(永久)" : "直接封禁 " + banDays + " 天")
                        + "，理由: " + reason, "");
        notifySupport.pushSystemNotify(userId, NOTIFY_ACCOUNT_BANNED, 0L, null,
                "因违反社区规范，你的账号已被"
                        + (banDays == 0 ? "永久封禁" : "封禁 " + banDays + " 天")
                        + (reason == null || reason.isBlank() ? "" : "。理由：" + reason.trim())
                        + "。如有异议，可在登录页提交封禁申诉。");
        log.info("direct ban: user={}, days={}, admin={}", userId, banDays, adminId);
    }

    @Override
    @Transactional
    public void unban(long adminId, long userId) {
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        if (!Integer.valueOf(1).equals(target.getStatus())) {
            // 幂等拒绝而非静默成功:管理员点解封说明他认为该账号被封着,
            // 状态对不上通常意味着「看的列表是旧的」,该让他刷新而非点头走人
            throw new BusinessException(UNPROCESSABLE, "该账号不在封禁中，请刷新列表");
        }
        banService.unban(userId, adminId);
        notifySupport.pushSystemNotify(userId, NOTIFY_ACCOUNT_UNBANNED, 0L, null,
                "你的账号已恢复正常，可以继续发帖和搭话。如对处置有疑问，可通过「意见反馈」联系管理员。");
        log.info("manual unban: user={}, admin={}", userId, adminId);
    }

    @Override
    @Transactional
    public void mute(long adminId, long userId, int muteDays, String reason) {
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(1).equals(target.getRole())) {
            throw new BusinessException(UNPROCESSABLE, "不能禁言管理员账号");
        }
        if (Integer.valueOf(1).equals(target.getStatus())) {
            // 封禁中的人本就不能发内容,再叠禁言只会让解封后的状态难以解释
            throw new BusinessException(UNPROCESSABLE, "账号处于封禁中，请先解封再处理禁言");
        }
        if (Integer.valueOf(2).equals(target.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "该账号已注销，无法禁言");
        }
        java.time.LocalDateTime until = muteService.mute(userId, muteDays);
        String why = reason == null ? "" : reason.trim();
        auditService.record(adminId, "account_mute", "user", userId,
                "直接禁言 " + muteDays + " 天，理由: " + why, "");
        notifySupport.pushSystemNotify(userId, NOTIFY_ACCOUNT_MUTED, 0L, null,
                "因违反社区规范，你的账号已被禁言 " + muteDays + " 天（"
                        + until.format(MUTE_TIME_FMT) + " 自动解除）。"
                        + "禁言期间可以浏览，但不能发帖、评论、私信和发送群消息。"
                        + (why.isEmpty() ? "" : "理由：" + why + "。")
                        + "如有异议，可在「意见反馈」申请复核。");
        log.info("direct mute: user={}, days={}, until={}, admin={}", userId, muteDays, until, adminId);
    }

    @Override
    @Transactional
    public void unmute(long adminId, long userId) {
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(1).equals(target.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "账号处于封禁中，请先解封再处理禁言");
        }
        java.time.LocalDateTime until = target.getMuteUntil();
        if (until == null || !until.isAfter(java.time.LocalDateTime.now())) {
            // 与解封同口径：拒绝而非静默成功，提示管理员刷新列表
            throw new BusinessException(UNPROCESSABLE, "该账号不在禁言中，请刷新列表");
        }
        muteService.unmute(userId);
        auditService.record(adminId, "account_unmute", "user", userId, "管理员手动解除禁言", "");
        notifySupport.pushSystemNotify(userId, NOTIFY_ACCOUNT_UNMUTED, 0L, null,
                "你的账号禁言已解除，可以继续发帖、评论、私信和发送群消息。"
                        + "如对处置有疑问，可通过「意见反馈」联系管理员。");
        log.info("manual unmute: user={}, admin={}", userId, adminId);
    }

    @Override
    public PendingSummaryVO pendingSummary() {
        PendingSummaryVO vo = new PendingSummaryVO();
        vo.setReportPending(reportMapper.countPending());
        /*
         * 机审待办按平台队列口径(2026-10-24 圈主协审后):未升级的圈内任务
         * 已派给圈主,不算平台管理员的活。口径必须与 /review/pending 的
         * 管理员分支同一条 SQL(countPendingPlatform),否则角标 N、队列却更短。
         */
        vo.setReviewPending(reviewTaskMapper.countPendingPlatform(
                java.time.LocalDateTime.now().minusHours(ownerEscalateHours)));
        vo.setVerifyPending(userMapper.selectCount(
                        new LambdaQueryWrapper<User>().eq(User::getVerifyStatus, 2))
                .intValue());
        vo.setFeedbackPending(feedbackMapper.countPending());
        vo.setFilePending(fileAssetMapper.countPendingAll());
        return vo;
    }
}
