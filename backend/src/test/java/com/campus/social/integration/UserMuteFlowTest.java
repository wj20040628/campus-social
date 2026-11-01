package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.AdminUserService;
import com.campus.social.service.ReportAdminService;
import com.campus.social.service.ReportService;
import com.campus.social.service.impl.PostWriteService;
import com.campus.social.vo.ReportAdminPageVO;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportVO;
import com.campus.social.vo.UserAdminPageVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 举报处置「禁言」闭环（2026-10-31）。
 *
 * <p>与封禁的区别：账号 status 仍为 0，可登录浏览；但发帖入口被
 * {@code UserMuteService} 拦截，禁言到期后自动恢复。自播种自清理。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class UserMuteFlowTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long ADMIN_ID = 1L;
    private static final int NOTIFY_MUTED = 24;
    private static final int NOTIFY_CONTENT_TAKEDOWN = 20;
    private static final int NOTIFY_REPORT_RESULT = 18;

    @Autowired
    private ReportService reportService;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private ReportAdminService reportAdminService;
    @Autowired
    private PostWriteService postWriteService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long reporterId;
    private Long adminTargetId;
    private Long postId;

    @BeforeEach
    void seed() {
        authorId = insertUser("mute-author-" + SUFFIX);
        reporterId = insertUser("mute-reporter-" + SUFFIX);
        adminTargetId = insertUser("mute-admin-" + SUFFIX);
        jdbc.update("UPDATE user SET role = 1 WHERE id = ?", adminTargetId);
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?, ?, 1, 0)",
                authorId, "禁言测试帖-" + SUFFIX);
        postId = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, authorId);
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{authorId, reporterId, adminTargetId}) {
            if (id != null) {
                jdbc.update("UPDATE user SET mute_until = NULL WHERE id = ?", id);
            }
        }
        jdbc.update("DELETE FROM notify WHERE user_id IN (?, ?) OR actor_id IN (?, ?)",
                authorId, reporterId, authorId, reporterId);
        jdbc.update("DELETE FROM review_task WHERE post_id IN (SELECT id FROM post WHERE author_id = ?)",
                authorId);
        jdbc.update("DELETE FROM audit_log WHERE (target_type = 'user' AND target_id IN (?, ?)) "
                        + "OR (target_type = 'report' AND target_id IN "
                        + "(SELECT id FROM report WHERE reporter_id = ?)) "
                        + "OR (target_type = 'post' AND target_id IN "
                        + "(SELECT id FROM post WHERE author_id = ?))",
                authorId, reporterId, reporterId, authorId);
        jdbc.update("DELETE FROM report WHERE reporter_id = ?", reporterId);
        jdbc.update("DELETE FROM post WHERE author_id = ?", authorId);
        jdbc.update("DELETE FROM user WHERE id IN (?, ?, ?)", authorId, reporterId, adminTargetId);
    }

    private Long pendingReport(int targetType, long targetId) {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (?,?,?,3,0,0)", reporterId, targetType, targetId);
        return jdbc.queryForObject(
                "SELECT id FROM report WHERE reporter_id = ? ORDER BY id DESC LIMIT 1", Long.class, reporterId);
    }

    private ReportHandleDTO dto(String action, Integer banDays, Integer muteDays) {
        return dto(action, banDays, muteDays, "禁言测试");
    }

    private ReportHandleDTO dto(String action, Integer banDays, Integer muteDays, String note) {
        ReportHandleDTO d = new ReportHandleDTO();
        d.setAction(action);
        d.setBanDays(banDays);
        d.setMuteDays(muteDays);
        d.setHandleNote(note);
        return d;
    }

    private int notifyCount(Long userId, int type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify WHERE user_id = ? AND type = ?", Integer.class, userId, type);
    }

    private String lastNotifyContent(Long userId, int type) {
        return jdbc.queryForObject(
                "SELECT content FROM notify WHERE user_id = ? AND type = ? ORDER BY id DESC LIMIT 1",
                String.class, userId, type);
    }

    private CreatePostDTO newPost(String content) {
        CreatePostDTO dto = new CreatePostDTO();
        dto.setContent(content);
        return dto;
    }

    @Test
    void muteSilencesAuthorAndTakesDownReportedContent() {
        Long reportId = pendingReport(2, postId);
        LocalDateTime before = LocalDateTime.now();
        ReportHandleVO vo = reportService.handle(ADMIN_ID, reportId, dto("mute", null, 7));

        assertEquals("mute", vo.getAction());
        assertEquals(1, vo.getStatus());
        assertNotNull(vo.getMutedUntil(), "禁言应回显截止时间");
        assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, authorId),
                "禁言不冻结账号，仍可登录浏览");
        Timestamp muteUntil = jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId);
        assertNotNull(muteUntil);
        assertTrue(muteUntil.toLocalDateTime().isAfter(before.plusDays(6)),
                "7 天禁言的截止时间应明显晚于当下");
        assertEquals(2, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId),
                "禁言成立应连带下架被举报内容");
        assertEquals(1, notifyCount(authorId, NOTIFY_MUTED), "作者应收到禁言通知");
        assertEquals(1, notifyCount(authorId, NOTIFY_CONTENT_TAKEDOWN), "内容被下架也应通知作者");
        assertEquals(1, notifyCount(reporterId, NOTIFY_REPORT_RESULT), "举报人应收到结果回执");
        assertEquals(1, countAudit("report_mute", "report", reportId), "禁言要留审计");
        assertEquals(1, countAudit("report_upheld", "user", authorId), "禁言计入举报成立/信誉分");

        BusinessException e = assertThrows(BusinessException.class,
                () -> postWriteService.create(authorId, newPost("禁言期间发帖-" + SUFFIX)));
        assertEquals(ResultCode.USER_MUTED.getCode(), e.getCode(), "禁言期间发帖应被 USER_MUTED 拦截");

        jdbc.update("UPDATE user SET mute_until = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", authorId);
        long newPostId = postWriteService.create(authorId, newPost("禁言解除后的测试帖-" + SUFFIX));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, newPostId),
                "禁言到期后应恢复发帖");
    }

    @Test
    void muteOnUserTargetLeavesPostUntouched() {
        Long reportId = pendingReport(1, authorId);
        ReportHandleVO vo = reportService.handle(ADMIN_ID, reportId, dto("mute", null, 3));

        assertEquals("mute", vo.getAction());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, authorId));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId),
                "用户举报没有具体内容，不应改动帖子");
        assertEquals(1, notifyCount(authorId, NOTIFY_MUTED));
        assertEquals(0, notifyCount(authorId, NOTIFY_CONTENT_TAKEDOWN), "没有内容可下架，不该发下架通知");
        assertEquals(1, notifyCount(reporterId, NOTIFY_REPORT_RESULT));
    }

    @Test
    void muteRequiresDaysAndRejectsAdminTarget() {
        Long reportId = pendingReport(2, postId);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> reportService.handle(ADMIN_ID, reportId, dto("mute", null, null)));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), missing.getCode(), "禁言天数必填");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE id = ? AND mute_until IS NOT NULL", Integer.class, authorId),
                "校验失败不应写禁言截止时间");

        Long adminReport = pendingReport(1, adminTargetId);
        BusinessException admin = assertThrows(BusinessException.class,
                () -> reportService.handle(ADMIN_ID, adminReport, dto("mute", null, 7)));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), admin.getCode(), "不能禁言管理员");
        assertNull(jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, adminTargetId),
                "管理员不应被写入禁言");
    }

    @Test
    void adminCanLiftMuteBeforeExpiry() {
        Long reportId = pendingReport(2, postId);
        reportService.handle(ADMIN_ID, reportId, dto("mute", null, 7));

        adminUserService.unmute(ADMIN_ID, authorId);

        assertNull(jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId),
                "解除后不应再有禁言截止时间");
        assertEquals(1, notifyCount(authorId, 25), "解除禁言应通知本人");
        assertEquals(1, countAudit("account_unmute", "user", authorId), "解除禁言要留审计");
        long newPostId = postWriteService.create(authorId, newPost("解除禁言后发帖-" + SUFFIX));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, newPostId),
                "解除后应立即恢复发帖");
    }

    @Test
    void unmuteRejectsUserWhoIsNotMuted() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> adminUserService.unmute(ADMIN_ID, authorId));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode(), "未禁言时解除应返回 422");
        assertEquals(0, notifyCount(authorId, 25), "失败操作不应发解除通知");
    }

    @Test
    void adminDirectMuteBlocksPostingAndNotifies() {
        LocalDateTime before = LocalDateTime.now();
        adminUserService.mute(ADMIN_ID, authorId, 3, "刷屏广告");

        Timestamp until = jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId);
        assertNotNull(until, "直接禁言应写入截止时间");
        assertTrue(until.toLocalDateTime().isAfter(before.plusDays(2)), "3 天禁言的截止时间应明显晚于当下");
        assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, authorId),
                "禁言不冻结账号");
        assertEquals(1, notifyCount(authorId, NOTIFY_MUTED), "被禁言人应收到 type=24 通知");
        String content = lastNotifyContent(authorId, NOTIFY_MUTED);
        assertTrue(content.contains("禁言 3 天"), "通知应写明时长");
        assertTrue(content.contains("刷屏广告"), "通知应带管理员写的理由");
        assertEquals(1, countAudit("account_mute", "user", authorId), "直接禁言要留审计");

        BusinessException e = assertThrows(BusinessException.class,
                () -> postWriteService.create(authorId, newPost("直接禁言后发帖-" + SUFFIX)));
        assertEquals(ResultCode.USER_MUTED.getCode(), e.getCode(), "直接禁言同样拦截发帖");
    }

    @Test
    void repeatedDirectMuteOnlyExtends() {
        adminUserService.mute(ADMIN_ID, authorId, 7, "首次禁言");
        Timestamp first = jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId);

        adminUserService.mute(ADMIN_ID, authorId, 1, "再次禁言");
        Timestamp second = jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId);
        assertNotNull(second);
        assertTrue(!second.toLocalDateTime().isBefore(first.toLocalDateTime()),
                "重复禁言只能延长，不能缩短已有截止时间");
    }

    @Test
    void directMuteRejectsAdminBannedAndMissingUser() {
        BusinessException admin = assertThrows(BusinessException.class,
                () -> adminUserService.mute(ADMIN_ID, adminTargetId, 7, "测试"));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), admin.getCode(), "不能禁言管理员");

        jdbc.update("UPDATE user SET status = 1 WHERE id = ?", authorId);
        BusinessException banned = assertThrows(BusinessException.class,
                () -> adminUserService.mute(ADMIN_ID, authorId, 7, "测试"));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), banned.getCode(), "封禁中不应再禁言");
        assertNull(jdbc.queryForObject(
                "SELECT mute_until FROM user WHERE id = ?", Timestamp.class, authorId),
                "校验失败不应写禁言截止时间");

        BusinessException missing = assertThrows(BusinessException.class,
                () -> adminUserService.mute(ADMIN_ID, 999999999L, 7, "测试"));
        assertEquals(ResultCode.NOT_FOUND.getCode(), missing.getCode(), "用户不存在应回 404");
    }

    @Test
    void adminPageFiltersMutedUsers() {
        adminUserService.mute(ADMIN_ID, authorId, 7, "筛选测试");

        UserAdminPageVO mutedPage = adminUserService.page("mute-author-" + SUFFIX, null, true, null, 50);
        assertEquals(1L, mutedPage.getTotal(), "禁言中筛选应命中被禁言账号");
        assertEquals(authorId, mutedPage.getList().get(0).getUserId());
        assertNotNull(mutedPage.getList().get(0).getMuteUntil(), "列表行应带禁言截止时间");

        // 到期后不再出现在「禁言中」：筛选按时间比较，不依赖清理任务
        jdbc.update("UPDATE user SET mute_until = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", authorId);
        assertEquals(0L, adminUserService.page("mute-author-" + SUFFIX, null, true, null, 50).getTotal(),
                "已过期的禁言不应再命中");

        // 封禁中即使 mute_until 仍在未来，也不属于「禁言中」——前端该行显示封禁且没有解除禁言按钮
        jdbc.update("UPDATE user SET status = 1, mute_until = DATE_ADD(NOW(), INTERVAL 7 DAY) WHERE id = ?", authorId);
        assertEquals(0L, adminUserService.page("mute-author-" + SUFFIX, null, true, null, 50).getTotal(),
                "封禁中的账号不应出现在禁言中筛选");
    }

    @Test
    void reportPersistsHandleActionAndDaysForHistory() {
        Long reportId = pendingReport(2, postId);
        reportService.handle(ADMIN_ID, reportId, dto("mute", null, 7));

        assertEquals("mute", jdbc.queryForObject(
                "SELECT handle_action FROM report WHERE id = ?", String.class, reportId));
        assertEquals(7, jdbc.queryForObject(
                "SELECT handle_days FROM report WHERE id = ?", Integer.class, reportId));

        ReportAdminPageVO page = reportAdminService.list(1, true, null, 50);
        ReportVO row = page.getList().stream()
                .filter(r -> r.getId().equals(reportId))
                .findFirst().orElseThrow();
        assertEquals("mute", row.getHandleAction(), "已处理列表应下发实际动作");
        assertEquals(7, row.getHandleDays(), "已处理列表应下发处罚天数");
    }

    @Test
    void punitiveReportActionsRequireHandleNote() {
        Long reportId = pendingReport(2, postId);

        for (String action : new String[]{"ban", "takedown"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> reportService.handle(ADMIN_ID, reportId, dto(action, 7, null, null)));
            assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode(), action + " 必须写处置理由");
        }
        BusinessException blankMute = assertThrows(BusinessException.class,
                () -> reportService.handle(ADMIN_ID, reportId, dto("mute", null, 7, "   ")));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), blankMute.getCode(), "空白理由不算填写");

        assertEquals(0, jdbc.queryForObject("SELECT status FROM report WHERE id = ?", Integer.class, reportId),
                "校验失败不应改动举报状态");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE id = ? AND mute_until IS NOT NULL", Integer.class, authorId));

        // 驳回不是处罚，理由可留空
        reportService.handle(ADMIN_ID, reportId, dto("reject", null, null, null));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM report WHERE id = ?", Integer.class, reportId));
    }

    private int countAudit(String action, String targetType, long targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_type = ? AND target_id = ?",
                Integer.class, action, targetType, targetId);
    }
}
