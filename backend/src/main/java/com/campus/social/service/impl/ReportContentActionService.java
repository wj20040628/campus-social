package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.entity.Report;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.service.AuditService;
import com.campus.social.vo.ReportHandleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.REPORT_ALREADY_HANDLED;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;
import static com.campus.social.service.impl.ReportConstants.ACTION_IGNORE;
import static com.campus.social.service.impl.ReportConstants.ACTION_REJECT;
import static com.campus.social.service.impl.ReportConstants.ACTION_TAKEDOWN;
import static com.campus.social.service.impl.ReportConstants.STATUS_CONFIRMED;
import static com.campus.social.service.impl.ReportConstants.STATUS_PENDING;
import static com.campus.social.service.impl.ReportConstants.STATUS_REJECTED;

/**
 * 不处罚账号的举报处置：仅下架、驳回、忽略（2026-11-14 从 ReportReviewService 拆出）。
 *
 * <p>与「成立并封禁 / 成立并禁言」分开：那两条是账号处罚，本类只动内容与举报状态。
 * 三条动作共享同一套乐观锁判据（{@code UPDATE ... WHERE status = 0}，影响行数 0
 * 即已被他人处置），也共享「驳回/忽略也回执举报人」的通知口径，放在一起
 * 避免三份实现各写一遍幂等判断。
 *
 * <p>忽略不改 status，故额外以 handler_id 作为「已被看过」的确定判据：
 * {@code markIgnored} 的 WHERE 带 {@code handler_id IS NULL}，
 * 首次 ignore 命中（affected=1），重复 ignore 必然落空（affected=0）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportContentActionService {

    /** 审计动作名 */
    private static final String AUDIT_REJECT = "report_reject";
    private static final String AUDIT_TAKEDOWN = "report_takedown";

    private final ReportMapper reportMapper;
    private final ReportTargetResolver targetResolver;
    private final ReportNoticeService noticeService;
    private final AuditService auditService;
    private final ReportContentTakedown contentTakedown;

    /**
     * 仅下架内容、不封禁作者（2026-10-28）。
     *
     * <p>适用面：内容违规（广告、擦边、引流）但人还值得留。此前这档只能
     * 二选一——驳回等于放过，封禁等于杀人，管理员于是拖着不处置。
     *
     * <p>没有内容可下架的目标（用户本人 / 未上线的搭子）直接 422 而不是
     * 静默成功：管理员点「仅下架」却发现什么都没发生时，会以为系统坏了。
     */
    public ReportHandleVO handleTakedown(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        int targetType = report.getTargetType() == null ? 0 : report.getTargetType();
        if (targetType == ReportTargetType.USER || targetType == ReportTargetType.BUDDY_POST) {
            throw new BusinessException(UNPROCESSABLE, "这条举报没有可下架的内容，请改选封禁或驳回");
        }
        // 作者先解析：内容一旦被下架，resolveUserId 的查询带 is_deleted=0 会查空
        long authorId = resolveTargetUserId(report);
        String note = requireNote(dto, "下架");
        if (reportMapper.handle(report.getId(), STATUS_CONFIRMED, adminId, now, note,
                ACTION_TAKEDOWN, null) == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        String takedown = contentTakedown.takedown(report);
        auditService.record(adminId, AUDIT_TAKEDOWN, "report", report.getId(),
                "举报成立，下架内容(" + takedown + ")，未封禁，备注 " + note, "");
        // 成立即计信誉分，与封禁口径一致（只下架不代表没违规）
        auditService.record(adminId, "report_upheld", "user", authorId,
                "举报#" + report.getId() + "成立,信誉-20", "");
        noticeService.notifyPunished(report, authorId, null, null, takedown, note);
        noticeService.notifyReporter(report, report.getTargetType() == ReportTargetType.CIRCLE
                ? "举报已核实成立，该圈子已被解散。"
                : "举报已核实成立，相关内容已下架。");
        log.info("report takedown: report={}, target={}#{}, admin={}",
                report.getId(), targetType, report.getTargetId(), adminId);
        return ReportHandleVO.builder()
                .reportId(report.getId())
                .status(STATUS_CONFIRMED)
                .action(ACTION_TAKEDOWN)
                .build();
    }

    /** 驳回：举报不成立 */
    public ReportHandleVO handleReject(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        String note = optionalNote(dto);
        if (reportMapper.handle(report.getId(), STATUS_REJECTED, adminId, now, note,
                ACTION_REJECT, null) == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        auditService.record(adminId, AUDIT_REJECT, "report", report.getId(), note, "");
        /*
         * 驳回也回执（2026-10-28）：举报人最怕的不是「不成立」，
         * 是不知道为什么、也没有人告诉他。不告知被举报人——举报不成立对他没有处置。
         */
        noticeService.notifyReporter(report, "经核实，你举报的内容暂未发现明确违规。感谢你一起维护校园环境。");
        log.info("report rejected: report={}, admin={}", report.getId(), adminId);
        return ReportHandleVO.builder()
                .reportId(report.getId())
                .status(STATUS_REJECTED)
                .action(ACTION_REJECT)
                .build();
    }

    /** 忽略：不改 status，仅记录处置人（用于「已阅未处置」） */
    public ReportHandleVO handleIgnore(long adminId, long reportId, ReportHandleDTO dto) {
        int affected = reportMapper.markIgnored(reportId, adminId, optionalNote(dto));
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        log.info("report ignored: report={}, admin={}", reportId, adminId);
        return ReportHandleVO.builder()
                .reportId(reportId)
                .status(STATUS_PENDING)
                .action(ACTION_IGNORE)
                .build();
    }

    /** 定位被举报内容的作者；查不到回 404 而不是默默处置举报人 */
    private long resolveTargetUserId(Report report) {
        Long authorId = targetResolver.resolveUserId(report);
        if (authorId == null) {
            throw new BusinessException(NOT_FOUND, "被举报内容已不存在或已被删除，无法定位作者");
        }
        return authorId;
    }

    /** 处罚类动作必须写理由：理由会原样进被处置人的站内通知与申诉材料 */
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