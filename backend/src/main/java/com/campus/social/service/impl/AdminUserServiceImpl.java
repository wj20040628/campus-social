package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.Post;
import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.FeedbackMapper;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AdminUserService;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.UserMuteService;
import com.campus.social.vo.AdminUserActionBriefVO;
import com.campus.social.vo.AdminUserCancelledInfoVO;
import com.campus.social.vo.AdminUserPostBriefVO;
import com.campus.social.vo.AdminUserReportBriefVO;
import com.campus.social.vo.PendingSummaryVO;
import com.campus.social.vo.UserAdminDetailVO;
import com.campus.social.vo.UserAdminItemVO;
import com.campus.social.vo.UserAdminPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final PostMapper postMapper;
    private final AuditLogMapper auditLogMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReportMapper reportMapper;
    private final FeedbackMapper feedbackMapper;
    private final FileAssetMapper fileAssetMapper;
    private final BanService banService;
    private final UserMuteService muteService;
    private final AuditService auditService;
    /** 列表里的信誉分与 ReviewTrustService 同一公式（2026-11-07） */
    private final ReviewTrustService reviewTrustService;
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

    /** 已注销账号的私信/通知留存天数；与 CancelledAccountPurgeJob 读同一个配置键 */
    @org.springframework.beans.factory.annotation.Value("${campus.account.cancelled-retention-days:180}")
    private int cancelledRetentionDays;

    @Override
    public UserAdminPageVO page(String keyword, Integer status, Boolean muted, Long cursor, int size) {
        return page(keyword, status, muted, cursor, size, null);
    }

    @Override
    public UserAdminPageVO page(String keyword, Integer status, Boolean muted, Long cursor, int size,
                                String sort) {
        int limit = Math.max(1, Math.min(size, 50));
        boolean recentPunish = "recentPunish".equals(sort);
        /*
         * 两种排序两种游标：
         *  - 注册时间：id 是单调唯一键，游标传上一页最后一个 id，翻页稳定；
         *  - 最近处置：排序值会变（用户又犯错就跳到前面），keyset 无法稳定，
         *    改用 offset。管理端列表量级小、翻页浅，offset 的漏行风险可接受。
         */
        int offset = recentPunish && cursor != null ? Math.max(0, cursor.intValue()) : 0;
        LambdaQueryWrapper<User> qw = baseQuery(keyword, status, muted);
        if (recentPunish) {
            qw.orderByDesc(User::getLastPunishTime)
                    .orderByDesc(User::getId)
                    .last("LIMIT " + offset + ", " + (limit + 1));
        } else {
            qw.lt(cursor != null, User::getId, cursor)
                    .orderByDesc(User::getId)
                    .last("LIMIT " + (limit + 1));
        }
        List<User> rows = userMapper.selectList(qw);
        boolean hasMore = rows.size() > limit;
        List<User> page = hasMore ? rows.subList(0, limit) : rows;

        // 本页的处置依据一次批量取回（2026-11-07）：信誉分 4 条分组查询 +
        // 被举报 1 条 UNION + 公开帖数 1 条，不再逐行 COUNT。
        java.util.List<Long> ids = page.stream().map(User::getId).toList();
        java.util.Map<Long, ReviewTrustService.CreditStat> credits =
                reviewTrustService.creditStats(ids);
        java.util.Map<Long, int[]> reports = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (java.util.Map<String, Object> row : reportMapper.countReportsForUsers(ids)) {
                Long uid = asLong(row.get("uid"));
                if (uid != null) {
                    reports.put(uid, new int[]{asInt(row.get("total_cnt"), row.get("totalCnt")),
                            asInt(row.get("upheld_cnt"), row.get("upheldCnt"))});
                }
            }
        }
        java.util.Map<Long, Integer> postCounts = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (java.util.Map<String, Object> row : postMapper.countPublicByAuthors(ids)) {
                Long uid = asLong(row.get("uid"));
                if (uid != null) {
                    postCounts.put(uid, asInt(row.get("cnt"), row.get("cnt")));
                }
            }
        }

        UserAdminPageVO vo = new UserAdminPageVO();
        vo.setList(page.stream().map(u -> toItem(u, credits, reports, postCounts)).toList());
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore
                ? (recentPunish ? (long) (offset + limit) : page.get(page.size() - 1).getId())
                : null);
        // total 是当前筛选的全量数:「搜出 3 个叫张三的人」与「本页只有 20 条」是两件事
        vo.setTotal(userMapper.selectCount(baseQuery(keyword, status, muted)));
        return vo;
    }

    /** 筛选条件单独成方法:分页查询与 total 计数必须是同一套 WHERE,否则两数对不上 */
    private LambdaQueryWrapper<User> baseQuery(String keyword, Integer status, Boolean muted) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(status != null, User::getStatus, status)
                /*
                 * 2026-11-07：「全部」不再混入已注销账号。
                 * 注销后昵称统一为「已注销用户」、资料全部清空，在列表里既认不出是谁、
                 * 也不能再封禁/禁言，只会把在册用户挤到后面。需要回查时走「已注销」筛选，
                 * 数据仍可检索（后台审计需要），只是不再占默认视图。
                 */
                .ne(status == null, User::getStatus, 2)
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

    private UserAdminItemVO toItem(User u,
            java.util.Map<Long, ReviewTrustService.CreditStat> credits,
            java.util.Map<Long, int[]> reports,
            java.util.Map<Long, Integer> postCounts) {
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

        ReviewTrustService.CreditStat credit = credits.get(u.getId());
        int[] report = reports.getOrDefault(u.getId(), new int[]{0, 0});
        item.setCreditScore(credit != null ? credit.score() : ReviewTrustService.SCORE_BASE);
        item.setBanCount(credit != null ? credit.bansTotal() : 0);
        item.setReportCount(report[0]);
        item.setUpheldCount(report[1]);
        item.setPostCount(postCounts.getOrDefault(u.getId(), 0));
        return item;
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static int asInt(Object snake, Object camel) {
        Object v = snake != null ? snake : camel;
        return v instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public UserAdminDetailVO detail(long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        UserAdminDetailVO vo = new UserAdminDetailVO();
        vo.setUser(toItem(u));

        vo.setReports(reportMapper.selectRecentForUser(userId, 20).stream()
                .map(this::toReportBrief).toList());

        List<AuditLog> trail = auditLogMapper.selectUserTrail(userId, 20);
        Set<Long> opIds = trail.stream().map(AuditLog::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        java.util.Map<Long, String> operators = opIds.isEmpty() ? java.util.Map.of()
                : userMapper.selectBatchIds(opIds).stream()
                        .collect(Collectors.toMap(User::getId,
                                x -> x.getNickname() == null ? "用户 #" + x.getId() : x.getNickname(),
                                (a, b) -> a));
        vo.setActions(trail.stream().map(a -> {
            AdminUserActionBriefVO brief = new AdminUserActionBriefVO();
            brief.setAction(a.getAction());
            brief.setDetail(a.getDetail());
            brief.setCreateTime(a.getCreateTime());
            brief.setOperator(a.getUserId() == null ? "系统"
                    : operators.getOrDefault(a.getUserId(), "管理员 #" + a.getUserId()));
            return brief;
        }).toList());

        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, userId)
                .orderByDesc(Post::getId)
                .last("LIMIT 10"));
        vo.setPosts(posts.stream().map(p -> {
            AdminUserPostBriefVO brief = new AdminUserPostBriefVO();
            brief.setId(p.getId());
            brief.setExcerpt(excerpt(p.getContent()));
            brief.setStatus(p.getStatus());
            brief.setLikeCount(p.getLikeCount());
            brief.setCommentCount(p.getCommentCount());
            brief.setCreateTime(p.getCreateTime());
            return brief;
        }).toList());

        if (Integer.valueOf(2).equals(u.getStatus())) {
            AuditLog cancel = auditLogMapper.selectLatest("account_cancel", "user", userId);
            java.time.LocalDateTime cancelledAt = cancel != null ? cancel.getCreateTime() : u.getUpdateTime();
            AdminUserCancelledInfoVO info = new AdminUserCancelledInfoVO();
            info.setCancelledAt(cancelledAt);
            info.setRetentionDays(cancelledRetentionDays);
            if (cancelledAt != null) {
                info.setPurgeDueAt(cancelledAt.plusDays(cancelledRetentionDays));
            }
            info.setPurgedAt(u.getPurgedAt());
            info.setPolicy("资料已于注销时清空；私信与通知保留 " + cancelledRetentionDays
                    + " 天后清理；帖子/评论与举报/审计记录去标识化保留。");
            vo.setCancelled(info);
        }
        return vo;
    }

    /** 单用户版列表行：详情页与列表使用同一套统计公式，避免两处口径漂移 */
    private UserAdminItemVO toItem(User u) {
        java.util.Map<Long, ReviewTrustService.CreditStat> credits =
                reviewTrustService.creditStats(List.of(u.getId()));
        java.util.Map<Long, int[]> reports = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : reportMapper.countReportsForUsers(List.of(u.getId()))) {
            Long uid = asLong(row.get("uid"));
            if (uid != null) {
                reports.put(uid, new int[]{asInt(row.get("total_cnt"), row.get("totalCnt")),
                        asInt(row.get("upheld_cnt"), row.get("upheldCnt"))});
            }
        }
        java.util.Map<Long, Integer> postCounts = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : postMapper.countPublicByAuthors(List.of(u.getId()))) {
            Long uid = asLong(row.get("uid"));
            if (uid != null) {
                postCounts.put(uid, asInt(row.get("cnt"), row.get("cnt")));
            }
        }
        return toItem(u, credits, reports, postCounts);
    }

    private AdminUserReportBriefVO toReportBrief(Report r) {
        AdminUserReportBriefVO brief = new AdminUserReportBriefVO();
        brief.setId(r.getId());
        brief.setTargetType(r.getTargetType());
        brief.setTargetId(r.getTargetId());
        brief.setReasonType(r.getReasonType());
        brief.setReasonText(r.getReasonText());
        brief.setStatus(r.getStatus());
        brief.setCreateTime(r.getCreateTime());
        brief.setHandleTime(r.getHandleTime());
        brief.setHandleAction(r.getHandleAction());
        brief.setHandleDays(r.getHandleDays());
        brief.setHandleNote(r.getHandleNote());
        return brief;
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
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
         * 机审待办 = 全部待审任务(2026-11-08 改):管理员队列现在也展示
         * 圈内任务,角标口径必须同一条 SQL(countPendingAll),否则角标 N、
         * 队列却更短。圈主协作只影响「谁先看到」,不影响总数。
         */
        vo.setReviewPending(reviewTaskMapper.countPendingAll());
        vo.setVerifyPending(userMapper.selectCount(
                        new LambdaQueryWrapper<User>().eq(User::getVerifyStatus, 2))
                .intValue());
        vo.setFeedbackPending(feedbackMapper.countPending());
        vo.setFilePending(fileAssetMapper.countPendingAll());
        return vo;
    }
}
