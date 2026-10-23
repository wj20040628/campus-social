package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.FeedbackHandleDTO;
import com.campus.social.dto.FeedbackSubmitDTO;
import com.campus.social.dto.StudentNoAppealDTO;
import com.campus.social.entity.Feedback;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FeedbackMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.FeedbackService;
import com.campus.social.service.MessageGateway;
import com.campus.social.service.impl.PostNotifySupport;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.FeedbackAdminItemVO;
import com.campus.social.vo.FeedbackAdminPageVO;
import com.campus.social.vo.FeedbackMineItemVO;
import com.campus.social.vo.FeedbackMinePageVO;
import com.campus.social.vo.FeedbackSubmitVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意见反馈实现（2026-10-18）。
 *
 * <p>三段职责：
 * <ul>
 *   <li>提交：敏感词 → 媒体序列化 → 落库</li>
 *   <li>列表：游标分页 + 待办总数（手机端红点要的是「还剩多少」）</li>
 *   <li>处置：乐观锁 UPDATE，0 行即已被他人处理，回 409</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    /** feedback.status */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_ADOPTED = 1;
    private static final int STATUS_IGNORED = 2;

    private static final String ACTION_ADOPT = "adopt";
    private static final String ACTION_IGNORE = "ignore";

    private static final String AUDIT_ADOPT = "feedback_adopt";
    private static final String AUDIT_IGNORE = "feedback_ignore";

    /** 单条反馈媒体上限，与发帖一致 */
    private static final int MEDIA_LIMIT = 9;

    /**
     * 认证申诉的 content 前缀（2026-10-19）。
     *
     * <p>{@code kind} 不落独立列，改由前缀承担：为一个布尔标记迁移表结构
     * 不划算，而这个前缀同时满足了「管理端一眼分辨」与「用户侧仍是正常反馈」。
     *
     * <p><b>前缀会占用 content 的 1000 字上限</b>：用户实际可写 1000 - 前缀长度。
     * 前缀刻意做得短（10 字）就是这个原因。
     */
    static final String APPEAL_PREFIX = "【认证申诉】";

    /** 判定一条反馈是否为认证申诉（管理端列表用） */
    public static boolean isAppeal(String content) {
        return content != null && content.startsWith(APPEAL_PREFIX);
    }

    private final FeedbackMapper feedbackMapper;
    private final SensitiveWordFilter sensitive;
    private final AuditService auditService;
    private final PostNotifySupport postNotify;
    private final ObjectMapper om;
    /** 申诉人没有账号，处理结果只能靠邮件触达其联系邮箱（见 sendAppealResultMail） */
    private final MessageGateway messageGateway;

    @Override
    @Transactional
    public FeedbackSubmitVO submit(long userId, FeedbackSubmitDTO dto) {
        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        // 与发帖 / 评论同一口径：反馈会被人看到，且会直接进入管理员审核队列
        if (sensitive.containsBlock(content)) {
            throw new BusinessException(ResultCode.SENSITIVE_WORD_BLOCKED);
        }

        // 认证申诉：加前缀落库，让管理端能与普通反馈区分（见 APPEAL_PREFIX 注释）
        if (dto.getKind() != null && dto.getKind() == 1) {
            content = APPEAL_PREFIX + content;
        }

        String mediaJson = serializeMedia(dto.getMedia());
        feedbackMapper.insertFeedback(userId, dto.getCategory(), content, mediaJson);

        Feedback created = feedbackMapper.selectById(feedbackMapper.selectLatestIdByUser(userId));
        log.info("feedback submitted: user={}, category={}, media={}",
                userId, dto.getCategory(), mediaJson == null ? 0 : 1);
        return FeedbackSubmitVO.builder()
                .feedbackId(created == null ? null : created.getId())
                .status(STATUS_PENDING)
                .build();
    }

    /**
     * 学号认证申诉（2026-10-19）：公开端点，提交方无账号、{@code user_id} 置 NULL。
     *
     * <p>content 由服务端拼接（前缀 + 学号 + 联系邮箱 + 选填说明），
     * 不把 content 的语义构造交给前端，也不对拼接后的整段跑敏感词（邮箱地址
     * 可能误伤），申诉队列由管理端人工核对。
     *
     * <p>主键取 {@code LAST_INSERT_ID()} 而非 {@code selectLatestIdByUser}：
     * 申诉人 user_id 为 NULL，按 user_id 回查会查不到刚插的行。
     */
    @Override
    @Transactional
    public FeedbackSubmitVO submitStudentNoAppeal(StudentNoAppealDTO dto) {
        String note = dto.getNote() == null ? "" : dto.getNote().trim();
        StringBuilder sb = new StringBuilder(APPEAL_PREFIX)
                .append("学号 ").append(dto.getStudentNo()).append(" 认证未通过。联系邮箱 ").append(dto.getEmail());
        if (!note.isEmpty()) {
            sb.append("；说明：").append(note);
        }
        String content = sb.toString();

        // category=2（使用问题）：与反馈内容分类语义对齐；申诉靠 content 前缀区分，不占独立列。
        // user_id 传 NULL —— 申诉人尚未注册成功，没有账号。
        feedbackMapper.insertFeedback(null, 2, content, null);

        // user_id 为 NULL 不能按用户回查，用同连接 LAST_INSERT_ID() 取刚插的主键。
        Long id = feedbackMapper.selectLastInsertId();
        log.info("student-no appeal submitted: studentNo={}, email={}, feedbackId={}",
                UserVoAssembler.maskUsername(dto.getStudentNo()),
                UserVoAssembler.maskUsername(dto.getEmail()), id);
        return FeedbackSubmitVO.builder()
                .feedbackId(id)
                .status(STATUS_PENDING)
                .build();
    }

    @Override
    public FeedbackAdminPageVO list(Integer status, Integer category, Integer kind, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<FeedbackAdminItemVO> rows = feedbackMapper.selectAdminList(status, category, kind, cursor, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<FeedbackAdminItemVO> page = hasMore ? rows.subList(0, limit) : rows;

        FeedbackAdminPageVO vo = new FeedbackAdminPageVO();
        vo.setList(page);
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        vo.setPendingCount(feedbackMapper.countPending());
        return vo;
    }

    @Override
    @Transactional
    public FeedbackSubmitVO handle(long adminId, long feedbackId, FeedbackHandleDTO dto) {
        Feedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) {
            throw new BusinessException(ResultCode.FEEDBACK_NOT_FOUND, "反馈记录不存在");
        }
        if (fb.getStatus() != null && fb.getStatus() != STATUS_PENDING) {
            throw new BusinessException(ResultCode.FEEDBACK_ALREADY_HANDLED);
        }

        String action = dto.getAction() == null ? "" : dto.getAction().trim();
        int target;
        if (ACTION_ADOPT.equals(action)) {
            target = STATUS_ADOPTED;
        } else if (ACTION_IGNORE.equals(action)) {
            target = STATUS_IGNORED;
        } else {
            throw new BusinessException(ResultCode.UNPROCESSABLE, "处置动作不合法，只能是 adopt / ignore");
        }

        int affected = feedbackMapper.handle(feedbackId, target, adminId,
                LocalDateTime.now(), dto.getHandleNote());
        if (affected == 0) {
            throw new BusinessException(ResultCode.FEEDBACK_ALREADY_HANDLED);
        }
        auditService.record(adminId, ACTION_ADOPT.equals(action) ? AUDIT_ADOPT : AUDIT_IGNORE,
                "feedback", feedbackId, dto.getHandleNote(), "");
        // 处置说明即回复。乐观锁已挡掉重复处置,这里不会重发。
        // 申诉人 user_id 为 NULL、没有账号:既不能把 null 传进 pushFeedbackReply 的
        // long 形参(拆箱 NPE 会回滚整个处置事务,导致采纳点了报错),站内通知也没有
        // 收件人。故申诉改为邮件把处理说明发到其联系邮箱,普通反馈仍推站内通知。
        if (fb.getUserId() != null) {
            postNotify.pushFeedbackReply(fb.getUserId(), feedbackId, adminId, dto.getHandleNote());
        } else {
            sendAppealResultMail(fb, dto.getHandleNote());
        }
        log.info("feedback handled: id={}, action={}, admin={}", feedbackId, action, adminId);

        return FeedbackSubmitVO.builder().feedbackId(feedbackId).status(target).build();
    }

    /**
     * 把申诉处理结果发到申诉人留下的联系邮箱（2026-10-19）。
     *
     * <p>申诉人在注册阶段被六层挡下，没有账号，站内通知无法触达；邮箱是唯一渠道。
     * 联系邮箱在提交申诉时由服务端拼进 content（「联系邮箱 xxx」），这里按同一措辞抽出。
     *
     * <p>邮件是「尽力而为」：发不出去也要保证处置结果已落库，故异常只记日志、不抛。
     */
    private void sendAppealResultMail(Feedback fb, String note) {
        String email = extractAppealEmail(fb.getContent());
        if (email == null) {
            log.warn("appeal content has no contact email, skip mail: feedbackId={}", fb.getId());
            return;
        }
        String text = (note == null || note.isBlank()) ? "你的学号认证申诉已处理。" : note.trim();
        String subject = "你的学号认证申诉处理结果 - 友声";
        String body = "<div style='font-family:sans-serif;padding:20px;line-height:1.7'>"
                + "<h2 style='color:#2b6cb0'>友声</h2>"
                + "<p>你的学号认证申诉已处理，管理员说明如下：</p>"
                + "<blockquote style='margin:12px 0;padding:8px 12px;border-left:3px solid #2b6cb0;"
                + "background:#f5f7fa;color:#333'>" + escapeHtml(text) + "</blockquote>"
                + "<p style='color:#999;font-size:13px'>如果仍有疑问，可直接回复本邮件。</p>"
                + "</div>";
        try {
            messageGateway.sendNotice(email, subject, body);
        } catch (Exception e) {
            log.warn("appeal result mail failed: feedbackId={}, email={}, err={}",
                    fb.getId(), UserVoAssembler.maskUsername(email), e.getMessage());
        }
    }

    /** 从申诉 content 抽出联系邮箱；抽不到返回 null */
    private static String extractAppealEmail(String content) {
        if (content == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("联系邮箱\\s*([^\\s；;，,]+)").matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /** 处理说明来自管理员自由输入，插入 HTML 前做最小转义，防止正文里的标签破坏邮件结构 */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public FeedbackMinePageVO listMine(long userId, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<FeedbackMineItemVO> rows = feedbackMapper.selectByUser(userId, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<FeedbackMineItemVO> page = hasMore ? rows.subList(0, limit) : rows;
        FeedbackMinePageVO vo = new FeedbackMinePageVO();
        vo.setList(page);
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        return vo;
    }

    /**
     * 媒体列表 → {@code media_json} 字符串。
     *
     * <p>输出用 {@code img/video} 字符串而不是 DTO 里的 1/2，与发帖的
     * {@code post.media} 完全同形 —— 管理端页面复用同一套缩略图渲染，
     * 不必在前端为反馈再写一份类型映射。
     *
     * <p>序列化失败不抛：这里的 DTO 只有一个 int 和一个 String，
     * Jackson 处理不了只可能是编码器被换掉这种环境级事故，
     * 那时整站都该 500，而不是让一条反馈提不上去。
     */
    private String serializeMedia(List<FeedbackSubmitDTO.MediaItem> media) {
        if (media == null || media.isEmpty()) {
            return null;
        }
        ArrayNode arr = om.createArrayNode();
        int n = 0;
        for (FeedbackSubmitDTO.MediaItem m : media) {
            if (m == null || m.getUrl() == null || m.getUrl().isBlank() || n >= MEDIA_LIMIT) {
                continue;
            }
            ObjectNode node = om.createObjectNode();
            node.put("type", m.getType() != null && m.getType() == 2 ? "video" : "img");
            node.put("url", m.getUrl());
            arr.add(node);
            n++;
        }
        if (arr.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            log.warn("feedback media serialize failed, fallback to null: {}", e.getMessage());
            return null;
        }
    }
}
