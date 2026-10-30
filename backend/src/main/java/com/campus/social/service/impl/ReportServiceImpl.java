package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.UserMapper;

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
    /**
     * 仅下架内容、不封人（2026-10-28）。
     *
     * <p>此前成立只有「封禁」一条路，轻微违规（一条广告评论、一次擦边图）
     * 只能判驳回（等于放过）或封号（过重）——两个都不对，
     * 管理员于是倾向于不处理。下架把「内容违规但人不至于封」这档补上。
     */
    private static final String ACTION_TAKEDOWN = "takedown";

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

    /** report.status */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_CONFIRMED = 1;
    private static final int STATUS_REJECTED = 2;

    /** 审计动作名 */
    private static final String AUDIT_BAN = "report_ban";
    private static final String AUDIT_REJECT = "report_reject";
    private static final String AUDIT_TAKEDOWN = "report_takedown";

    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final BanService banService;
    private final AuditService auditService;
    /** 处置结果通知（2026-10-28）：举报人、被下架/被封禁的作者 */
    private final PostNotifySupport notifySupport;
    /** 举报阈值折叠(2026-10-24):同一帖子独立举报人达到阈值即折叠进人审 */
    private final ReportFoldService foldService;
    /** 举报集中自动封禁(2026-10-25):24h 内多名认证举报人 → 限期封禁止血 */
    private final ReportAutoBanService autoBanService;
    /** 举报目标 → 作者的归因(2026-10-25 抽出,与自动封禁共用一份 switch) */
    private final ReportTargetResolver targetResolver;
    /** 举报时目标快照(2026-10-29):作者删帖后管理员仍能看到原文/作者/媒体 */
    private final ReportSnapshotService snapshotService;
    /*
     * 下架不再需要各表的 Mapper：
     * 统一走 reportMapper 的 takedownXxx（显式 UPDATE），
     * 原因见 takedownContent 上的注释（updateById 会排除逻辑删除字段）。
     */

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
            /*
             * 快照在插入举报前抓取：作者随后删帖/删评论，管理员仍能看到举报时的原文。
             * 抓取失败不影响举报主流程（captureQuietly 返回 null）。
             */
            String snapshot = snapshotService.captureQuietly(dto.getTargetType(), dto.getTargetId());
            reportMapper.insertReport(reporterId, dto.getTargetType(), dto.getTargetId(),
                    dto.getReasonType(), dto.getReasonText(), snapshot);
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

        /*
         * 阈值折叠检查(2026-10-24):与举报同一事务——计数包含本条未提交的行,
         * 回滚时折叠/任务/通知一并回滚。幂等重复(duplicated)不触发:
         * 同一人重复举报既不增加独立举报人数,也不该反复进入折叠分支。
         */
        foldService.maybeFoldPost(dto.getTargetType(), dto.getTargetId());

        /*
         * 集中举报自动封禁检查(2026-10-25):注册到事务提交后执行——
         * 封禁含不可回滚的 Redis 写,举报若回滚不能留下"人被封了"的幽灵。
         * 幂等重复不触发,理由同上。
         */
        if (created != null) {
            autoBanService.scheduleCheckAfterCommit(created);
        }

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
            case ACTION_TAKEDOWN -> handleTakedown(adminId, report, dto, now);
            case ACTION_REJECT -> handleReject(adminId, report, dto, now);
            case ACTION_IGNORE -> handleIgnore(adminId, reportId, dto);
            default -> throw new BusinessException(UNPROCESSABLE,
                    "处置动作不合法，只能是 ban / takedown / reject / ignore");
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
        notifyPunished(report, targetUserId, banDays, bannedUntil, takedown, dto.getHandleNote());
        notifyReporter(report, report.getTargetType() == ReportTargetType.USER
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
     * 举报成立时,按 targetType 把被举报内容一并下架/隐藏。
     *
     * <p>仅封作者、不下架内容,是举报系统最常见的「判了等于没判」:
     * 用户举报一条色情帖,管理员判成立→作者被封,但那条帖子和它的评论照常展示,
     * 举报人下次还看得到,系统公信力直接归零。
     *
     * <p>返回被下架内容的简短描述(用于审计与日志);无内容可下架时返回 null。
     */
    private String takedownContent(Report report) {
        /*
         * 【全部改用 ReportMapper 里的显式 UPDATE，不再 updateById(entity)】
         *
         * 2026-10-21 修：项目配了 MyBatis-Plus 逻辑删除（logic-delete-field: isDeleted），
         * updateById 会主动排除被登记的逻辑删除字段，于是
         * `c.setIsDeleted(1); updateById(c)` **静默失效** ——
         * 日志打「下架成功」、数据库里 is_deleted 仍是 0、内容照常展示。
         * 实测：举报成立并封禁后，被举报的聊天消息 is_deleted 仍为 0。
         *
         * 现在每条都是显式 UPDATE（见 ReportMapper.takedownXxx）：
         * SQL 直接写 is_deleted / status，不经过 MP 的字段过滤；
         * 返回值是真实影响行数，所以「已下架过」与「刚下架」能区分开。
         */
        return switch (report.getTargetType()) {
            case ReportTargetType.POST -> { // status 1→2 下架(与机审驳回同口径)
                yield reportMapper.takedownPost(report.getTargetId()) > 0
                        ? "帖子#" + report.getTargetId()
                        : "帖子(已下架或已删)";
            }
            case ReportTargetType.COMMENT -> { // 软删 + 置下架态
                yield reportMapper.takedownComment(report.getTargetId()) > 0
                        ? "评论#" + report.getTargetId()
                        : "评论(已下架或已删)";
            }
            case ReportTargetType.CHAT_MESSAGE -> { // 软删隐藏(私聊内容只在双方会话里)
                yield reportMapper.takedownChatMessage(report.getTargetId()) > 0
                        ? "消息#" + report.getTargetId()
                        : "消息(已下架或已删)";
            }
            case ReportTargetType.GROUP_MESSAGE -> { // 软删隐藏(群消息列表按 is_deleted 过滤)
                yield reportMapper.takedownGroupMessage(report.getTargetId()) > 0
                        ? "群消息#" + report.getTargetId()
                        : "群消息(已下架或已删)";
            }
            /*
             * 集市活动（targetType=6，2026-10-21 新增）。
             *
             * 【为什么不复用 5】5 在契约里是「搭子（buddy_post）」—— 预留表已删除、无端点，
             * 处置会被 resolveTargetUserId 拦下并回 422「搭子需上线后处理」。
             * 而前端 MktDetail 此前传的正是 5，导致集市活动举报**能提交、不能处置**。
             * 给集市单独一个编号，才是让「封禁作者 + 下架活动」这条链路真正可用。
             *
             * 用 soft_delete 而不是 is_enable：is_enable=0 表示「发布者主动暂停」，
             * 广场查询会把两者都过滤掉，但语义上「管理员强制下架」与「作者自己暂停」
             * 是两回事，混用会让发布者以为自己误点了暂停、进而重新开启它。
             */
            case ReportTargetType.MKT_ACTIVITY -> {
                yield reportMapper.takedownMktActivity(report.getTargetId()) > 0
                        ? "集市活动#" + report.getTargetId()
                        : "集市活动(已下架或已删)";
            }
            /*
             * 文件（targetType=7，2026-10-27）。
             * 只置 status=3 下架，不物理删：文件对象与审核记录要留作申诉证据；
             * 删除对象由 FilePurgeJob 在 7 天后处理。
             */
            case ReportTargetType.FILE -> {
                yield reportMapper.takedownFile(report.getTargetId()) > 0
                        ? "文件#" + report.getTargetId()
                        : "文件(已下架或已删)";
            }
            /*
             * 圈子（targetType=9，2026-10-29）。
             * 「仅下架」对圈子就是解散：status=1 后列表/详情/搜索全过滤，
             * 圈内帖子级联 status=2，与圈主解散同一口径。不封圈主——
             * 圈子违规不等于圈主个人该被封，管理员要封人可另选「成立并封禁」。
             */
            case ReportTargetType.CIRCLE -> {
                if (reportMapper.takedownCircle(report.getTargetId()) > 0) {
                    reportMapper.takedownCirclePosts(report.getTargetId());
                    yield "圈子#" + report.getTargetId();
                }
                yield "圈子(已解散或已删)";
            }
            default -> null; // 1用户 / 5搭子(未上线):无内容可下架
        };
    }

    /**
     * 仅下架内容、不封禁作者（2026-10-28）。
     *
     * <p>适用面：内容违规（广告、擦边、引流）但人还值得留。此前这档只能
     * 二选一——驳回等于放过，封禁等于杀人，管理员于是拖着不处置。
     *
     * <p>没有内容可下架的目标（用户本人 / 未上线的搭子）直接 422 而不是
     * 静默成功：管理员点「仅下架」却发现什么都没发生时，会以为系统坏了。
     */
    private ReportHandleVO handleTakedown(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        int targetType = report.getTargetType() == null ? 0 : report.getTargetType();
        if (targetType == ReportTargetType.USER || targetType == ReportTargetType.BUDDY_POST) {
            throw new BusinessException(UNPROCESSABLE, "这条举报没有可下架的内容，请改选封禁或驳回");
        }
        // 作者先解析：内容一旦被下架，resolveUserId 的查询带 is_deleted=0 会查空
        long authorId = resolveTargetUserId(report);
        if (reportMapper.handle(report.getId(), STATUS_CONFIRMED, adminId, now, dto.getHandleNote()) == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        String takedown = takedownContent(report);
        auditService.record(adminId, AUDIT_TAKEDOWN, "report", report.getId(),
                "举报成立，下架内容(" + takedown + ")，未封禁"
                        + (dto.getHandleNote() == null ? "" : "，备注 " + dto.getHandleNote()), "");
        // 成立即计信誉分，与封禁口径一致（只下架不代表没违规）
        auditService.record(adminId, "report_upheld", "user", authorId,
                "举报#" + report.getId() + "成立,信誉-20", "");
        notifyPunished(report, authorId, null, null, takedown, dto.getHandleNote());
        notifyReporter(report, report.getTargetType() == ReportTargetType.CIRCLE
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
    private void notifyPunished(Report report, long authorId, Integer banDays,
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

    /**
     * 通知举报人处置结果（2026-10-28）。
     *
     * <p>举报人此前拿不到任何结果：举报完就石沉大海，下次干脆不报了。
     * 忽略（ignore）不发——「已阅未处置」没有结论可回执，硬发一条
     * 「已忽略」只会让举报人觉得被敷衍。
     */
    private void notifyReporter(Report report, String verdict) {
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

    /** 驳回：举报不成立 */
    private ReportHandleVO handleReject(long adminId, Report report, ReportHandleDTO dto, LocalDateTime now) {
        int affected = reportMapper.handle(report.getId(), STATUS_REJECTED, adminId, now, dto.getHandleNote());
        if (affected == 0) {
            throw new BusinessException(REPORT_ALREADY_HANDLED, "该举报已被处置，请勿重复操作");
        }
        auditService.record(adminId, AUDIT_REJECT, "report", report.getId(), dto.getHandleNote(), "");
        /*
         * 驳回也回执（2026-10-28）：举报人最怕的不是「不成立」，
         * 是不知道为什么、也没有人告诉他。不告知被举报人——举报不成立对他没有处置。
         */
        notifyReporter(report, "经核实，你举报的内容暂未发现明确违规。感谢你一起维护校园环境。");
        log.info("report rejected: report={}, admin={}", report.getId(), adminId);
        return ReportHandleVO.builder()
                .reportId(report.getId())
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
}