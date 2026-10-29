package com.campus.social.integration;

import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.service.BanService;
import com.campus.social.service.ReportAdminService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportAdminPageVO;
import com.campus.social.vo.ReportSubmitVO;
import com.campus.social.vo.ReportVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圈子举报闭环（2026-10-29）。
 *
 * <p>自评估报告承诺「用户可对圈子发起举报」且管理员可解散圈子。这里锁住：
 * <ol>
 *   <li>targetType=9 能提交，管理端能看到圈名与圈主</li>
 *   <li>「仅下架」= 解散圈子（status=1）并级联隐藏圈内帖子，不封圈主</li>
 *   <li>圈主收 23 圈子解散通知，举报人收 18 结果回执</li>
 *   <li>「成立并封禁」同时封圈主 + 解散圈子</li>
 *   <li>驳回不动圈子</li>
 * </ol>
 * 自播种自清理，连本机 MySQL + Redis（local profile）。
 */
@SpringBootTest
@ActiveProfiles("local")
class CircleReportIntegrationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long ADMIN_ID = 1L;
    private static final int NOTIFY_REPORT_RESULT = 18;
    private static final int NOTIFY_ACCOUNT_BANNED = 19;
    private static final int NOTIFY_CIRCLE_TAKEDOWN = 23;

    @Autowired
    private ReportService reportService;
    @Autowired
    private ReportAdminService reportAdminService;
    @Autowired
    private BanService banService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long ownerId;
    private Long reporterId;
    private Long circleId;
    private Long postId;

    @BeforeEach
    void seed() {
        ownerId = insertUser("cr-owner-" + SUFFIX);
        reporterId = insertUser("cr-reporter-" + SUFFIX);
        jdbc.update("INSERT INTO circle (name, owner_id, member_count, status, is_deleted) "
                + "VALUES (?, ?, 1, 0, 0)", "圈子举报测试-" + SUFFIX, ownerId);
        circleId = jdbc.queryForObject(
                "SELECT id FROM circle WHERE owner_id = ? ORDER BY id DESC LIMIT 1", Long.class, ownerId);
        jdbc.update("INSERT INTO post (author_id, content, status, circle_id, is_deleted) "
                + "VALUES (?, ?, 1, ?, 0)", ownerId, "圈内测试帖-" + SUFFIX, circleId);
        postId = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? AND circle_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, ownerId, circleId);
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        if (ownerId != null) {
            banService.unban(ownerId, ADMIN_ID);
        }
        for (Long id : new Long[]{ownerId, reporterId}) {
            if (id != null) {
                jdbc.update("DELETE FROM notify WHERE user_id = ? OR actor_id = ?", id, id);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'report' AND target_id IN "
                        + "(SELECT id FROM report WHERE reporter_id = ?)", id);
                jdbc.update("DELETE FROM report WHERE reporter_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        if (circleId != null) {
            jdbc.update("DELETE FROM post WHERE circle_id = ?", circleId);
            jdbc.update("DELETE FROM circle WHERE id = ?", circleId);
        }
    }

    private ReportSubmitDTO submitDto(int targetType, long targetId) {
        ReportSubmitDTO d = new ReportSubmitDTO();
        d.setTargetType(targetType);
        d.setTargetId(targetId);
        d.setReasonType(3);
        d.setReasonText("圈子举报测试");
        return d;
    }

    private ReportHandleDTO handleDto(String action, Integer days, String note) {
        ReportHandleDTO d = new ReportHandleDTO();
        d.setAction(action);
        d.setBanDays(days);
        d.setHandleNote(note);
        return d;
    }

    private Long submitReport() {
        ReportSubmitVO vo = reportService.submit(reporterId, submitDto(9, circleId));
        assertNotNull(vo.getReportId());
        return vo.getReportId();
    }

    private int circleStatus() {
        return jdbc.queryForObject("SELECT status FROM circle WHERE id = ?", Integer.class, circleId);
    }

    private int postStatus() {
        return jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId);
    }

    private int userStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, id);
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

    @Test
    void submitShowsCircleNameAndOwnerInAdminList() {
        Long reportId = submitReport();

        ReportAdminPageVO page = reportAdminService.list(0, false, null, 20);
        ReportVO row = page.getList().stream()
                .filter(r -> reportId.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待审列表里找不到刚提交的圈子举报"));

        assertEquals(9, row.getTargetType());
        assertTrue(row.getTargetSummary().contains("圈子举报测试-" + SUFFIX), "摘要应是圈名");
        assertEquals(ownerId, row.getTargetAuthorId(), "归因应是圈主");
        assertEquals("cr-owner-" + SUFFIX, row.getTargetAuthorNickname());
    }

    @Test
    void takedownDissolvesCircleWithoutBanningOwner() {
        Long reportId = submitReport();

        reportService.handle(ADMIN_ID, reportId, handleDto("takedown", null, "圈子含违规内容"));

        assertEquals(1, circleStatus(), "仅下架应把圈子置 status=1");
        assertEquals(2, postStatus(), "圈内帖子应级联隐藏");
        assertEquals(0, userStatus(ownerId), "仅下架不封圈主");

        assertEquals(1, notifyCount(ownerId, NOTIFY_CIRCLE_TAKEDOWN), "圈主应收到圈子解散通知");
        String content = lastNotifyContent(ownerId, NOTIFY_CIRCLE_TAKEDOWN);
        assertTrue(content.contains("圈子举报测试-" + SUFFIX), "通知应带圈名");
        assertTrue(content.contains("解散"), "通知应说明已解散");
        assertEquals(1, notifyCount(reporterId, NOTIFY_REPORT_RESULT), "举报人应收到结果回执");
        assertTrue(lastNotifyContent(reporterId, NOTIFY_REPORT_RESULT).contains("解散"));

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'report_takedown' AND target_type = 'report' "
                        + "AND target_id = ?", Integer.class, reportId);
        assertEquals(1, audits, "仅下架要留审计");
        Integer upheld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'report_upheld' AND target_type = 'user' "
                        + "AND target_id = ?", Integer.class, ownerId);
        assertEquals(1, upheld, "成立举报应给圈主记信誉分");
    }

    @Test
    void banDissolvesCircleAndBansOwner() {
        Long reportId = submitReport();

        reportService.handle(ADMIN_ID, reportId, handleDto("ban", 1, "多次违规"));

        assertEquals(1, circleStatus(), "封禁成立应同时解散圈子");
        assertEquals(2, postStatus());
        assertEquals(1, userStatus(ownerId), "圈主应被封禁");
        assertEquals(1, notifyCount(ownerId, NOTIFY_ACCOUNT_BANNED), "圈主应收到封禁通知");
        assertEquals(1, notifyCount(ownerId, NOTIFY_CIRCLE_TAKEDOWN), "圈主应收到圈子解散通知");
        assertEquals(1, notifyCount(reporterId, NOTIFY_REPORT_RESULT));
    }

    @Test
    void rejectLeavesCircleUntouched() {
        Long reportId = submitReport();

        reportService.handle(ADMIN_ID, reportId, handleDto("reject", null, "不构成违规"));

        assertEquals(0, circleStatus(), "驳回不改圈子状态");
        assertEquals(1, postStatus(), "驳回不隐藏帖子");
        assertEquals(1, notifyCount(reporterId, NOTIFY_REPORT_RESULT));
        assertEquals(0, notifyCount(ownerId, NOTIFY_CIRCLE_TAKEDOWN));
        assertNotNull(jdbc.queryForObject(
                "SELECT handle_time FROM report WHERE id = ?", java.time.LocalDateTime.class, reportId),
                "驳回应写处置时间");
    }
}
