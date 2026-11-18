package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.UserMuteService;
import com.campus.social.vo.ReportHandleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.REPORT_ALREADY_HANDLED;
import static com.campus.social.common.ResultCode.REPORT_NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;
import static com.campus.social.service.impl.ReportConstants.ACTION_BAN;
import static com.campus.social.service.impl.ReportConstants.ACTION_IGNORE;
import static com.campus.social.service.impl.ReportConstants.ACTION_MUTE;
import static com.campus.social.service.impl.ReportConstants.ACTION_REJECT;
import static com.campus.social.service.impl.ReportConstants.ACTION_TAKEDOWN;
import static com.campus.social.service.impl.ReportConstants.STATUS_CONFIRMED;
import static com.campus.social.service.impl.ReportConstants.STATUS_PENDING;

/**
 * 举报处置（2026-10-30 从 ReportServiceImpl 拆出）。
 *
 * <p>处置走乐观锁 {@code UPDATE ... WHERE status = 0}，影响行数 0 即
 * 「已被他人处置」，回 409。ignore 不改 status，故额外以 handler_id
 * 作为「已被看过」的确定判据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportReviewService {

    /** 禁言上限。再长等同于事实上的永久禁言，封禁才是对应的重档 */
    private static final int MAX_MUTE_DAYS = 365;

    /** 审计动作名（内容处置的审计在 ReportContentActionService） */
    private static final String AUDIT_BAN = "report_ban";
    private static final String AUDIT_MUTE = "report_mute";

    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final BanService banService;
    private final UserMuteService muteService;
    private final AuditService auditService;
    /** 举报目标 → 作者的归因(2026-10-25 抽出,与自动封禁共用一份 switch) */
    private final ReportTargetResolver targetResolver;
    private final ReportNoticeService noticeService;
    /** 仅下架 / 驳回 / 忽略：不处罚账号的动作单独成类，避免本类继续膨胀 */
    private final ReportContentActionService contentActions;
    private final ReportContentTakedown contentTakedown;

    public ReportHandleVO handle(long adminId, long reportId, ReportHandleDTO dto) {
        var report = reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getIsDeleted())) {
            throw new BusinessException(REPORT_NOT_FOUND, "举报记录不存在");
        }
        if (!Integer.valueOf(STATUS_PENDING).equals(report.getStatus())) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        // ignore 之后 status 仍是 0（契约要求「不改变 status」），
        // 但 handler_id 已经填了——这条记录已被某个管理员看过，
        // 再让第二个人处置就会把前一个人的处置痕迹悄悄覆盖掉。
        // 故按 handler_id 也拦一道，与 markIgnored 的 WHERE 条件保持同一口径。
        if (report.getHandlerId() != null) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }

        String action = dto.getAction() == null ? "" : dto.getAction().trim();
        LocalDateTime now = LocalDateTime.now();
        return switch (action) {
            case ACTION_BAN -> handleBan(adminId, report, dto, now);
            case ACTION_MUTE -> handleMute(adminId, report, dto, now);
            case ACTION_TAKEDOWN -> contentActions.handleTakedown(adminId, report, dto, now);
            case ACTION_REJECT -> contentActions.handleReject(adminId, report, dto, now);
            case ACTION_IGNORE -> contentActions.handleIgnore(adminId, reportId, dto);
            default -> throw new BusinessException(UNPROCESSABLE,
                    "处置动作不合法，只能是 ban / mute / takedown / reject / ignore");
        };
    }

    /** 成立并封禁目标用户 */
    private ReportHandleVO handleBan(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        Integer banDays = dto.getBanDays();
        if (banDays == null || banDays < 0) {
            throw new BusinessException(UNPROCESSABLE, "封禁必须指定天数，0 表示永久");
        }
        // 被封禁的是内容的作者，不是内容本身（按 targetType 解析，圈子=圈主）
        long targetUserId = resolveTargetUserId(report);
        /*
         * 管理员护栏(2026-10-25 审计补):手动封禁(AdminUserServiceImpl.ban)对
         * 管理员 422,举报处置封禁此前漏了同款护栏——成立一条针对管理员的举报
         * 就能把唯一管理员冻结,后台锁死。与手动封禁同一规则、同一文案。
         */
        User banTarget = userMapper.selectById(targetUserId);
        if (banTarget != null && Integer.valueOf(1).equals(banTarget.getRole())) {
            throw new BusinessException(UNPROCESSABLE, "不能封禁管理员账号");
        }
        if (banTarget != null && Integer.valueOf(2).equals(banTarget.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "该账号已注销，无法封禁");
        }
        // 理由校验放在对象定位之后：先告诉管理员「这个人不能封 / 内容没了」，
        // 再要求他补理由，否则会被一句「请写理由」挡住更关键的错误
        String note = requireNote(dto, "封禁");

        int affected = reportMapper.handle(report.getId(), STATUS_CONFIRMED, adminId, now, note,
                ACTION_BAN, banDays);
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        LocalDateTime bannedUntil = banService.ban(targetUserId, banDays);
        // 举报的是内容(帖子/评论/消息)时,只封作者不够——被举报的色情/广告内容
        // 若不一并下架,举报就形同虚设。故成立时同步下架对应内容。
        // 举报对象是用户(targetType=1)时无内容可下架,这里是空操作。
        String takedown = contentTakedown.takedown(report);
        auditService.record(adminId, AUDIT_BAN, "report", report.getId(),
                "封禁用户 " + targetUserId + "，天数 " + banDays
                        + (takedown == null ? "" : "，下架内容(" + takedown + ")")
                        + "，备注 " + note, "");
        /*
         * user 级成立审计(2026-10-25,信誉分计分源):举报对象无论是用户本人
         * 还是其帖子/评论/消息/集市活动,成立即给作者记一次 report_upheld,
         * 信誉分 -20(180 天窗口)。此前只有 target_type=1 会进前科判定,
         * 内容举报成立却查无记录——信誉分制把这条盲区修掉。
         */
        auditService.record(adminId, "report_upheld", "user", targetUserId,
                "举报#" + report.getId() + "成立,信誉-20", "");
        /*
         * 通知(2026-10-28 补):此前成立只写审计,举报人永远不知道结果,
         * 被处置人只看到账号突然不能发帖——两端都是黑箱。
         */
        noticeService.notifyPunished(report, targetUserId, banDays, bannedUntil, takedown, note);
        noticeService.notifyReporter(report, report.getTargetType() == ReportTargetType.USER
                ? "举报已核实成立，该用户已被封禁。"
                : report.getTargetType() == ReportTargetType.CIRCLE
                        ? "举报已核实成立，圈主已被封禁，圈子已解散。"
                        : "举报已核实成立，发布者已被封禁，相关内容已下架。");
        log.info("report ban: report={}, targetUser={}, days={}, takedown={}, admin={}",
                report.getId(), targetUserId, banDays, takedown, adminId);
        return ReportHandleVO.builder()
                .reportId(report.getId())
                .status(STATUS_CONFIRMED)
                .action(ACTION_BAN)
                .bannedUntil(bannedUntil)
                .build();
    }

    /**
     * 成立并禁言（2026-10-31）：账号不冻结，可登录、浏览、收通知，
     * 但禁言期内不能发帖/评论/私信/群消息（集市与文件上传不拦）。
     *
     * <p>内容举报与封禁同口径：连带下架被举报内容，避免「处罚了人、违规内容还挂着」。
     * 圈子举报不走禁言——圈子本身没有可禁言对象，请选解散或封禁圈主。
     */
    private ReportHandleVO handleMute(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        Integer muteDays = dto.getMuteDays();
        if (muteDays == null || muteDays < 1 || muteDays > MAX_MUTE_DAYS) {
            throw new BusinessException(UNPROCESSABLE,
                    "禁言天数需在 1~" + MAX_MUTE_DAYS + " 天之间");
        }
        if (Integer.valueOf(ReportTargetType.CIRCLE).equals(report.getTargetType())) {
            throw new BusinessException(UNPROCESSABLE,
                    "圈子举报没有可禁言的对象，请选择解散圈子或封禁圈主");
        }
        long targetUserId = resolveTargetUserId(report);
        User target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new BusinessException(NOT_FOUND, "被举报用户不存在");
        }
        if (Integer.valueOf(1).equals(target.getRole())) {
            throw new BusinessException(UNPROCESSABLE, "不能禁言管理员账号");
        }
        if (Integer.valueOf(1).equals(target.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "该账号已被封禁，无需禁言");
        }
        if (Integer.valueOf(2).equals(target.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "该账号已注销，无法禁言");
        }
        String note = requireNote(dto, "禁言");

        int affected = reportMapper.handle(report.getId(), STATUS_CONFIRMED, adminId, now, note,
                ACTION_MUTE, muteDays);
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        LocalDateTime mutedUntil = muteService.mute(targetUserId, muteDays);
        String takedown = contentTakedown.takedown(report);
        auditService.record(adminId, AUDIT_MUTE, "report", report.getId(),
                "禁言用户 " + targetUserId + "，天数 " + muteDays
                        + (takedown == null ? "" : "，下架内容(" + takedown + ")")
                        + "，备注 " + note, "");
        // 禁言也是处置成立，与 ban/takedown 同口径计信誉分
        auditService.record(adminId, "report_upheld", "user", targetUserId,
                "举报#" + report.getId() + "成立,信誉-20", "");
        noticeService.notifyMuted(report, targetUserId, muteDays, mutedUntil, takedown, note);
        noticeService.notifyReporter(report,
                Integer.valueOf(ReportTargetType.USER).equals(report.getTargetType())
                        ? "举报已核实成立，该用户已被禁言 " + muteDays + " 天。"
                        : "举报已核实成立，该用户已被禁言 " + muteDays + " 天，相关内容已下架。");
        log.info("report mute: report={}, targetUser={}, days={}, takedown={}, admin={}",
                report.getId(), targetUserId, muteDays, takedown, adminId);
        return ReportHandleVO.builder()
                .reportId(report.getId())
                .status(STATUS_CONFIRMED)
                .action(ACTION_MUTE)
                .mutedUntil(mutedUntil)
                .build();
    }

    /**
     * 解析被封禁的用户 id。
     *
     * <p>{@code targetType} 各取值的处置对象各不相同：
     * 1用户→ targetId 本身；2帖子→ post.author_id；3评论→ comment.user_id；
     * 4私聊消息→ chat_message.sender_id；5搭子→ 预留表已删除（未上线）；
     * 6集市活动→ mkt_activity.user_id；7文件→ file_asset.user_id；
     * 8群消息→ group_message.sender_id；9圈子→ circle.owner_id。
     *
     * <p>任一环查不到都回 404 而不是默默封举报人：封错对象的代价
     * （误封无辜用户）远大于让管理员多点一次重新定位。
     */
    private long resolveTargetUserId(Report report) {
        Long authorId = targetResolver.resolveUserId(report);
        if (authorId == null) {
            throw new BusinessException(NOT_FOUND, "被举报内容已不存在或已被删除，无法定位作者");
        }
        return authorId;
    }

    /**
     * 处罚类动作（ban/mute/takedown）必须写理由（2026-10-31）。
     *
     * <p>理由会原样进被处置人的站内通知与申诉材料。没有理由的处罚，
     * 用户端只看到「你被禁言 7 天」，无法判断是否误判，只能反复找管理员。
     */
    private String requireNote(ReportHandleDTO dto, String actionLabel) {
        String note = optionalNote(dto);
        if (note == null) {
            throw new BusinessException(UNPROCESSABLE,
                    actionLabel + "必须填写处置理由，理由会同步告知被处置人");
        }
        return note;
    }

    /** 可选备注统一去空白；空串按 null 落库，避免列表里出现一行看不见的「处置说明」 */
    private String optionalNote(ReportHandleDTO dto) {
        String note = dto.getHandleNote();
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }
}
