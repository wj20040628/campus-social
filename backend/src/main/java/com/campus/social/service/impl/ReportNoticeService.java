package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.entity.Report;
import com.campus.social.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 举报处置结果通知（2026-10-30 从 ReportServiceImpl 拆出）。
 *
 * <p>举报人此前拿不到任何结果：举报完就石沉大海，下次干脆不报了。
 * 被处置人只看到内容消失或账号突然不能发帖——两端都是黑箱。
 */
@Service
@RequiredArgsConstructor
public class ReportNoticeService {

    /** notify.type：举报结果回执（发给举报人） */
    private static final int NOTIFY_REPORT_RESULT = 18;
    /** notify.type：账号被封禁（发给被处置人） */
    private static final int NOTIFY_ACCOUNT_BANNED = 19;
    /** notify.type：内容被下架（发给被处置人） */
    private static final int NOTIFY_CONTENT_TAKEDOWN = 20;
    /** notify.type：文件下架（复用 15，前端已有「去我的文件看理由」的跳转） */
    private static final int NOTIFY_FILE_TAKEDOWN = 15;
    /** notify.type：圈子被解散（发给圈主，2026-10-29 加；点击跳圈子详情） */
    private static final int NOTIFY_CIRCLE_TAKEDOWN = 23;

    private final PostNotifySupport notifySupport;
    private final ReportMapper reportMapper;
    private final ReportTargetResolver targetResolver;

    /**
     * 通知被处置人（2026-10-28）：封禁与内容下架。
     *
     * <p>两件事分成两条通知而不是合并：账号被封与某条内容被删是两个后果，
     * 合并成一段话会让「我只被删了一条评论，为什么账号也停了」更难分辨。
     * 被处置人当时可能登不进来（封禁拦截登录），通知落库等他解封后可见，
     * 登录页同时会显示解封时间与申诉入口。
     *
     * <p>actor 留空、不暴露处置管理员；理由取 handleNote（管理员备注，
     * 契约已声明会同步给被处置人）。
     */
    public void notifyPunished(Report report, long authorId, Integer banDays,
            LocalDateTime bannedUntil, String takedownDesc, String handleNote) {
        String reason = handleNote == null || handleNote.isBlank() ? "" : "处置说明：" + handleNote.trim() + "。";
        if (banDays != null) {
            String span = banDays <= 0 ? "永久封禁"
                    : "封禁 " + banDays + " 天" + (bannedUntil == null ? ""
                            : "（" + bannedUntil.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 自动解封）");
            notifySupport.pushSystemNotify(authorId, NOTIFY_ACCOUNT_BANNED, 0L, null,
                    "因违反社区规范，你的账号已被" + span + "。" + reason
                            + "如有异议，可在登录页提交封禁申诉。");
        }
        if (takedownDesc != null) {
            int tt = report.getTargetType() == null ? 0 : report.getTargetType();
            if (tt == ReportTargetType.CIRCLE) {
                // 圈子没有「内容下架」这个说法，用独立 type 23：前端点击跳圈子详情，
                // 文案也直接说「被解散」。复用 20 会被误解成某条帖子被删。
                String circleName = reportMapper.selectCircleSummary(report.getTargetId());
                String subject = circleName == null || circleName.isBlank()
                        ? "你创建的圈子"
                        : "你创建的圈子「" + circleName + "」";
                notifySupport.pushSystemNotify(authorId, NOTIFY_CIRCLE_TAKEDOWN,
                        report.getTargetId(), null,
                        subject + "因被举报核实违规，已被解散。" + reason
                                + "如有异议，可在「意见反馈」申请复核。");
            } else {
                // 文件走 15（前端已有「去我的文件看理由」入口），其余内容走 20
                notifySupport.pushSystemNotify(authorId,
                        tt == ReportTargetType.FILE ? NOTIFY_FILE_TAKEDOWN : NOTIFY_CONTENT_TAKEDOWN,
                        report.getTargetId(),
                        tt == ReportTargetType.POST ? report.getTargetId() : null,
                        "你发布的" + targetLabel(report.getTargetType()) + "因被举报核实违规，已下架。" + reason
                                + "如有异议，可在「意见反馈」申请复核。");
            }
        }
    }

    /** 通知举报人处置结果（2026-10-28）；ignore 不调用，见 ReportReviewService */
    public void notifyReporter(Report report, String verdict) {
        Long authorId = targetResolver.resolveUserId(report);
        if (authorId != null && report.getReporterId() != null
                && authorId.longValue() == report.getReporterId().longValue()) {
            return; // 自举报：同一个人不收两条自相矛盾的通知
        }
        notifySupport.pushSystemNotify(report.getReporterId(), NOTIFY_REPORT_RESULT,
                report.getId(), null, verdict);
    }

    /** 用户可读的内容类型名（通知正文用；审计里用的是「帖子#123」那种定位串） */
    private String targetLabel(Integer targetType) {
        if (targetType == null) {
            return "内容";
        }
        return switch (targetType) {
            case ReportTargetType.POST -> "帖子";
            case ReportTargetType.COMMENT -> "评论";
            case ReportTargetType.CHAT_MESSAGE -> "私信消息";
            case ReportTargetType.GROUP_MESSAGE -> "群消息";
            case ReportTargetType.MKT_ACTIVITY -> "集市活动";
            case ReportTargetType.FILE -> "文件";
            case ReportTargetType.CIRCLE -> "圈子";
            default -> "内容";
        };
    }
}
