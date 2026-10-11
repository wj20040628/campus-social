package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.entity.Report;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.CommentMapper;
import com.campus.social.mapper.ChatMessageMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.REPORT_ALREADY_HANDLED;
import static com.campus.social.common.ResultCode.REPORT_NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 举报服务实现（F4）。
 *
 * <p>幂等与并发两道防线：
 * <ul>
 *   <li>唯一索引 {@code uk_report(reporter_id,target_type,target_id)} 兜底，
 *       插入用<b>纯 INSERT</b>让唯一键抛 {@code DuplicateKeyException}，而不是
 *       {@code ON DUPLICATE KEY UPDATE}——后者把冲突吞掉且返回值不可靠
 *       （Connector/J 默认带 CLIENT_FOUND_ROWS，冲突赋回原值也返回 1），
 *       调用方的 {@code affected == 0} 分支就成了永不执行的死代码</li>
 *   <li>处置走乐观锁 {@code UPDATE ... WHERE status = 0}，
 *       影响行数 0 即「已被他人处置」，回 409</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /** 处置动作 */
    private static final String ACTION_BAN = "ban";
    private static final String ACTION_REJECT = "reject";
    private static final String ACTION_IGNORE = "ignore";

    /** report.status */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_CONFIRMED = 1;
    private static final int STATUS_REJECTED = 2;

    /** 审计动作名 */
    private static final String AUDIT_BAN = "report_ban";
    private static final String AUDIT_REJECT = "report_reject";

    private final ReportMapper reportMapper;
    private final BanService banService;
    private final AuditService auditService;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    @Transactional
    public ReportSubmitVO submit(long reporterId, ReportSubmitDTO dto) {
        // 先查一次：命中就是幂等重复，直接回显原记录。
        // 这不是并发保护（并发时两个请求可能都查不到），
        // 真正的兜底是唯一索引 uk_report 抛 DuplicateKeyException。
        var existing = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
        if (existing != null) {
            return duplicated(existing);
        }

        try {
            reportMapper.insertReport(reporterId, dto.getTargetType(), dto.getTargetId(),
                    dto.getReasonType(), dto.getReasonText());
        } catch (DuplicateKeyException e) {
            // 并发窗口内另一个请求刚插进同一条唯一键。回查拿 id，不能当成失败报错——
            // 对用户来说「重复举报」不是错误。
            var again = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
            if (again == null) {
                // 撞了唯一键却查不到行，只可能是这行被别人硬删了（正常业务不该发生）。
                // 此时回 500 让上层感知，而不是凭空造一个 reportId 返回给用户。
                log.warn("report unique key hit but row vanished: reporter={}, type={}, target={}",
                        reporterId, dto.getTargetType(), dto.getTargetId());
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "举报提交异常，请重试");
            }
            return duplicated(again);
        }

        var created = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
        log.info("report submitted: reporter={}, type={}, target={}, reason={}",
                reporterId, dto.getTargetType(), dto.getTargetId(), dto.getReasonType());
        return ReportSubmitVO.builder()
                .reportId(created == null ? null : created.getId())
                .duplicated(false)
                .status(STATUS_PENDING)
                .build();
    }

    /** 幂等重复：回显既有记录，不新增行 */
    private ReportSubmitVO duplicated(Report report) {
        return ReportSubmitVO.builder()
                .reportId(report.getId())
                .duplicated(true)
                .status(report.getStatus())
                .build();
    }

    @Override
    @Transactional
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
            case ACTION_REJECT -> handleReject(adminId, reportId, dto, now);
            case ACTION_IGNORE -> handleIgnore(adminId, reportId, dto);
            default -> throw new BusinessException(UNPROCESSABLE, "处置动作不合法，只能是 ban / reject / ignore");
        };
    }

    /** 成立并封禁目标用户 */
    private ReportHandleVO handleBan(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        Integer banDays = dto.getBanDays();
        if (banDays == null || banDays < 0) {
            throw new BusinessException(UNPROCESSABLE, "封禁必须指定天数，0 表示永久");
        }
        // 被封禁的是内容的作者，不是内容本身（targetType 五种取值分别解析）
        long targetUserId = resolveTargetUserId(report);

        int affected = reportMapper.handle(report.getId(), STATUS_CONFIRMED, adminId, now, dto.getHandleNote());
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        LocalDateTime bannedUntil = banService.ban(targetUserId, banDays);
        // 举报的是内容(帖子/评论/消息)时,只封作者不够——被举报的色情/广告内容
        // 若不一并下架,举报就形同虚设。故成立时同步下架对应内容。
        // 举报对象是用户(targetType=1)时无内容可下架,这里是空操作。
        String takedown = takedownContent(report);
        auditService.record(adminId, AUDIT_BAN, "report", report.getId(),
                "封禁用户 " + targetUserId + "，天数 " + banDays
                        + (takedown == null ? "" : "，下架内容(" + takedown + ")")
                        + "，备注 " + dto.getHandleNote(), "");
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
     * 举报成立时,按 targetType 把被举报内容一并下架/隐藏。
     *
     * <p>仅封作者、不下架内容,是举报系统最常见的「判了等于没判」:
     * 用户举报一条色情帖,管理员判成立→作者被封,但那条帖子和它的评论照常展示,
     * 举报人下次还看得到,系统公信力直接归零。
     *
     * <p>返回被下架内容的简短描述(用于审计与日志);无内容可下架时返回 null。
     */
    private String takedownContent(Report report) {
        return switch (report.getTargetType()) {
            case 2 -> { // 帖子:status 1→2 下架(与机审驳回同口径)
                var post = postMapper.selectById(report.getTargetId());
                if (post != null && !Integer.valueOf(2).equals(post.getStatus())) {
                    post.setStatus(2);
                    postMapper.updateById(post);
                    yield "帖子#" + post.getId();
                }
                yield post == null ? "帖子(已删)" : null;
            }
            case 3 -> { // 评论:直接软删 + 置下架态
                var c = commentMapper.selectById(report.getTargetId());
                if (c != null && !Integer.valueOf(1).equals(c.getIsDeleted())) {
                    c.setIsDeleted(1);
                    c.setStatus(2);
                    commentMapper.updateById(c);
                    yield "评论#" + c.getId();
                }
                yield c == null ? "评论(已删)" : null;
            }
            case 4 -> { // 聊天消息:软删隐藏(私聊内容只在双方会话里,下架即隐藏)
                var m = chatMessageMapper.selectById(report.getTargetId());
                if (m != null && !Integer.valueOf(1).equals(m.getIsDeleted())) {
                    m.setIsDeleted(1);
                    chatMessageMapper.updateById(m);
                    yield "消息#" + m.getId();
                }
                yield m == null ? "消息(已删)" : null;
            }
            default -> null; // 1用户 / 5搭子:无内容可下架
        };
    }

    /** 驳回：举报不成立 */
    private ReportHandleVO handleReject(long adminId, long reportId, ReportHandleDTO dto, LocalDateTime now) {
        int affected = reportMapper.handle(reportId, STATUS_REJECTED, adminId, now, dto.getHandleNote());
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        auditService.record(adminId, AUDIT_REJECT, "report", reportId, dto.getHandleNote(), "");
        log.info("report rejected: report={}, admin={}", reportId, adminId);
        return ReportHandleVO.builder()
                .reportId(reportId)
                .status(STATUS_REJECTED)
                .action(ACTION_REJECT)
                .build();
    }

    /**
     * 忽略：不改 status，仅记录处置人（用于「已阅未处置」）。
     *
     * <p><b>幂等判定必须看 handler_id，不能看 update 的影响行数</b>：
     * {@code markIgnored} 里带 {@code update_time = NOW()}，
     * 而 NOW() 精确到秒——同秒内两次调用 SQL 文本完全一致，
     * MySQL 仍会报「影响 0 行」；跨秒则报「影响 1 行」。
     * 结果是这个 affected 判定时而生效时而失效，同一行为两次请求给出不同答案。
     *
     * <p>故在 SQL 的 WHERE 里加 {@code handler_id IS NULL}，
     * 让「有没有被处置过」成为一条确定的前置条件而非概率事件：
     * 首次 ignore 命中（affected=1），重复 ignore 必然落空（affected=0），
     * 与 openapi「同一举报重复处置返回 409」一致。
     */
    private ReportHandleVO handleIgnore(long adminId, long reportId, ReportHandleDTO dto) {
        int affected = reportMapper.markIgnored(reportId, adminId, dto.getHandleNote());
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

    /**
     * 解析被封禁的用户 id。
     *
     * <p>{@code targetType} 五种取值的处置对象各不相同：
     * 1用户→ targetId 本身；2帖子→ post.author_id；3评论→ comment.user_id；
     * 4聊天消息→ chat_message.sender_id；
     * 5搭子→ buddy_post（MVP+ 第二批，表已建但无端点，故不支持封禁）。
     *
     * <p>任一环查不到都回 404 而不是默默封举报人：封错对象的代价
     * （误封无辜用户）远大于让管理员多点一次重新定位。
     */
    private long resolveTargetUserId(Report report) {
        Long authorId = switch (report.getTargetType()) {
            case 1 -> report.getTargetId();
            case 2 -> reportMapper.selectPostAuthorId(report.getTargetId());
            case 3 -> reportMapper.selectCommentAuthorId(report.getTargetId());
            case 4 -> reportMapper.selectChatMessageSenderId(report.getTargetId());
            default -> throw new BusinessException(UNPROCESSABLE,
                    "搭子内容的封禁需在搭子模块上线后处理，请改为驳回或忽略");
        };
        if (authorId == null) {
            throw new BusinessException(NOT_FOUND, "被举报内容已不存在或已被删除，无法定位作者");
        }
        return authorId;
    }
}