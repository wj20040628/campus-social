package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.BanService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportHandleVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 举报处置闭环（2026-10-28）。
 *
 * <p>本轮加的三件事各一条断言链：
 * <ul>
 *   <li>{@code takedown}：成立但只下架内容，不封作者</li>
 *   <li>处置结果通知：举报人收 18、被下架作者收 20、被封禁作者再收 19</li>
 *   <li>驳回也回执举报人（18），但不打扰被举报人</li>
 * </ul>
 * 自播种自清理，连本机 MySQL + Redis（local profile）。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReportHandleFlowTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long ADMIN_ID = 1L;

    @Autowired
    private ReportService reportService;
    @Autowired
    private BanService banService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long reporterId;
    private Long postId;

    @BeforeEach
    void seed() {
        authorId = insertUser("rh-author-" + SUFFIX);
        reporterId = insertUser("rh-reporter-" + SUFFIX);
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?, ?, 1, 0)",
                authorId, "举报处置测试帖-" + SUFFIX);
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
        if (authorId != null) {
            // 封禁写的是 Redis，用户删了计划还在；先解封再删人
            banService.unban(authorId, ADMIN_ID);
        }
        for (Long id : new Long[]{authorId, reporterId}) {
            if (id != null) {
                jdbc.update("DELETE FROM notify WHERE user_id = ? OR actor_id = ?", id, id);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                // 举报域审计按 report id 落，先删审计再删举报（子查询口径一致）
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'report' AND target_id IN "
                        + "(SELECT id FROM report WHERE reporter_id = ?)", id);
                jdbc.update("DELETE FROM report WHERE reporter_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        if (postId != null) {
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
    }

    /** 插一条待审举报（targetType=2 帖子），返回 report id */
    private Long pendingReport() {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (?,2,?,3,0,0)", reporterId, postId);
        return jdbc.queryForObject(
                "SELECT id FROM report WHERE reporter_id = ? ORDER BY id DESC LIMIT 1", Long.class, reporterId);
    }

    private ReportHandleDTO dto(String action, Integer days, String note) {
        ReportHandleDTO d = new ReportHandleDTO();
        d.setAction(action);
        d.setBanDays(days);
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

    @Test
    void takedownRemovesContentWithoutBanningAuthor() {
        Long reportId = pendingReport();
        ReportHandleVO vo = reportService.handle(ADMIN_ID, reportId, dto("takedown", null, "广告引流"));

        assertEquals(1, vo.getStatus());
        assertEquals("takedown", vo.getAction());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId),
                "仅下架应把帖子置 status=2");
        assertEquals(0, jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, authorId),
                "仅下架不封作者");

        assertEquals(1, notifyCount(authorId, 20), "作者应收到内容下架通知");
        assertTrue(lastNotifyContent(authorId, 20).contains("广告引流"), "通知应带处置说明");
        assertEquals(1, notifyCount(reporterId, 18), "举报人应收到结果回执");
        assertEquals(0, notifyCount(authorId, 19), "没有封禁就不该发封禁通知");

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'report_takedown' AND target_type = 'report' "
                        + "AND target_id = ?", Integer.class, reportId);
        assertEquals(1, audits, "仅下架要留审计");
    }

    @Test
    void rejectNotifiesReporterOnly() {
        Long reportId = pendingReport();
        reportService.handle(ADMIN_ID, reportId, dto("reject", null, "不构成违规"));

        assertEquals(2, jdbc.queryForObject("SELECT status FROM report WHERE id = ?", Integer.class, reportId));
        assertEquals(1, notifyCount(reporterId, 18));
        assertTrue(lastNotifyContent(reporterId, 18).contains("暂未发现"), "驳回回执应说明核实结论");
        assertEquals(0, notifyCount(authorId, 20), "驳回不处置内容，不该通知作者");
        assertEquals(0, notifyCount(authorId, 19));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId),
                "驳回不该改动帖子状态");
    }

    @Test
    void banNotifiesBothContentAndAccount() {
        Long reportId = pendingReport();
        ReportHandleVO vo = reportService.handle(ADMIN_ID, reportId, dto("ban", 7, "色情低俗"));

        assertEquals(1, vo.getStatus());
        assertNotNull(vo.getBannedUntil(), "限期封禁应回显解封时间");
        assertEquals(1, jdbc.queryForObject("SELECT status FROM user WHERE id = ?", Integer.class, authorId));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM post WHERE id = ?", Integer.class, postId));
        assertEquals(1, notifyCount(authorId, 19), "作者应收到封禁通知");
        assertTrue(lastNotifyContent(authorId, 19).contains("7 天"), "封禁通知应写明时长");
        assertEquals(1, notifyCount(authorId, 20), "内容被连带下架也应通知");
        assertEquals(1, notifyCount(reporterId, 18));
    }

    @Test
    void takedownRejectsUserTarget() {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (?,1,?,1,0,0)", reporterId, authorId);
        Long reportId = jdbc.queryForObject(
                "SELECT id FROM report WHERE reporter_id = ? ORDER BY id DESC LIMIT 1", Long.class, reporterId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(ADMIN_ID, reportId, dto("takedown", null, null)));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode(), "用户举报没有内容可下架");
        assertEquals(0, notifyCount(reporterId, 18), "失败处置不该发回执");
    }

    @Test
    void ignoreStaysSilent() {
        Long reportId = pendingReport();
        ReportHandleVO vo = reportService.handle(ADMIN_ID, reportId, dto("ignore", null, null));

        assertEquals(0, vo.getStatus());
        assertEquals(0, notifyCount(reporterId, 18), "忽略没有结论可回执，不发通知");
        assertEquals(0, notifyCount(authorId, 19));
        assertEquals(0, notifyCount(authorId, 20));
        assertNull(vo.getBannedUntil());
    }
}
