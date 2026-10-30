package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.BanService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 举报服务契约（2026-10-30，拆分 ReportServiceImpl 前补的特征测试）。
 *
 * <p>覆盖原实现里散落但没有断言的守卫：
 * <ul>
 *   <li>同一人重复提交同一目标：不新增行，回显原 reportId 且 duplicated=true</li>
 *   <li>已处置的举报再处置：409，不得覆盖前一个管理员的操作</li>
 *   <li>ignore 不改 status，但写 handler_id；重复 ignore 同样 409</li>
 *   <li>非法 action：422</li>
 *   <li>举报对象是管理员：禁止封禁（与手动封禁同一护栏）</li>
 *   <li>目标内容已不存在：404，不误封举报人</li>
 * </ul>
 * 自播种自清理，连测试库 + Redis 15 号库。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ReportContractTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long HANDLER_ID = 1L;

    @Autowired
    private ReportService reportService;
    @Autowired
    private BanService banService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long reporterId;
    private Long adminAuthorId;
    private Long postId;
    private Long adminPostId;

    @BeforeEach
    void seed() {
        authorId = insertUser("rc-author-" + SUFFIX, 0);
        reporterId = insertUser("rc-reporter-" + SUFFIX, 0);
        adminAuthorId = insertUser("rc-admin-" + SUFFIX, 1);
        postId = insertPost(authorId, "举报契约测试帖-" + SUFFIX);
        adminPostId = insertPost(adminAuthorId, "管理员测试帖-" + SUFFIX);
    }

    private Long insertUser(String username, int role) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,?,1,1,1,0)",
                username, "x", username, role);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertPost(long author, String content) {
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?,?,1,0)",
                author, content);
        return jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, author);
    }

    @AfterEach
    void cleanup() {
        if (authorId != null) {
            banService.unban(authorId, HANDLER_ID);
        }
        for (Long id : new Long[]{authorId, reporterId, adminAuthorId}) {
            if (id == null) {
                continue;
            }
            jdbc.update("DELETE FROM notify WHERE user_id = ? OR actor_id = ?", id, id);
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'report' AND target_id IN "
                    + "(SELECT id FROM report WHERE reporter_id = ?)", id);
            jdbc.update("DELETE FROM report WHERE reporter_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        for (Long id : new Long[]{postId, adminPostId}) {
            if (id != null) {
                jdbc.update("DELETE FROM post WHERE id = ?", id);
            }
        }
    }

    private ReportSubmitDTO submitDto(int targetType, long targetId) {
        ReportSubmitDTO dto = new ReportSubmitDTO();
        dto.setTargetType(targetType);
        dto.setTargetId(targetId);
        dto.setReasonType(3);
        dto.setReasonText("契约测试");
        return dto;
    }

    private ReportHandleDTO handleDto(String action, Integer days) {
        ReportHandleDTO dto = new ReportHandleDTO();
        dto.setAction(action);
        dto.setBanDays(days);
        return dto;
    }

    /** 直接插一条待审举报，绕开 submit 的幂等逻辑 */
    private Long pendingReport(int targetType, long targetId) {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (?,?,?,3,0,0)", reporterId, targetType, targetId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM report WHERE reporter_id = ? ORDER BY id DESC LIMIT 1", Long.class, reporterId);
        return id;
    }

    private int reportCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM report WHERE reporter_id = ?", Integer.class, reporterId);
    }

    @Test
    void duplicateSubmitReturnsExistingWithoutNewRow() {
        ReportSubmitVO first = reportService.submit(reporterId, submitDto(2, postId));
        ReportSubmitVO second = reportService.submit(reporterId, submitDto(2, postId));

        assertFalse(first.getDuplicated(), "首次提交不是重复");
        assertTrue(second.getDuplicated(), "第二次应标记为重复");
        assertEquals(first.getReportId(), second.getReportId(), "重复提交应回显同一条记录");
        assertEquals(1, reportCount(), "重复提交不得新增行");
    }

    @Test
    void secondHandlingIsRejected() {
        Long reportId = pendingReport(2, postId);
        reportService.handle(HANDLER_ID, reportId, handleDto("reject", null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(HANDLER_ID, reportId, handleDto("ban", 7)));
        assertEquals(ResultCode.REPORT_ALREADY_HANDLED.getCode(), e.getCode());
        assertEquals(2, jdbc.queryForObject(
                "SELECT status FROM report WHERE id = ?", Integer.class, reportId), "原处置结果不得被覆盖");
    }

    @Test
    void ignoreMarksHandlerButKeepsStatus() {
        Long reportId = pendingReport(2, postId);
        ReportHandleVO vo = reportService.handle(HANDLER_ID, reportId, handleDto("ignore", null));

        assertEquals(0, vo.getStatus(), "ignore 按契约保持待审状态");
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM report WHERE id = ?", Integer.class, reportId));
        assertEquals(HANDLER_ID, jdbc.queryForObject(
                "SELECT handler_id FROM report WHERE id = ?", Long.class, reportId), "ignore 应记录处置人");

        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(HANDLER_ID, reportId, handleDto("ignore", null)));
        assertEquals(ResultCode.REPORT_ALREADY_HANDLED.getCode(), e.getCode(), "重复 ignore 应被 handler_id 拦下");
    }

    @Test
    void invalidActionIsUnprocessable() {
        Long reportId = pendingReport(2, postId);
        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(HANDLER_ID, reportId, handleDto("delete-forever", null)));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM report WHERE id = ?", Integer.class, reportId), "非法动作不得改状态");
    }

    @Test
    void banningAdminIsRejected() {
        Long reportId = pendingReport(2, adminPostId);
        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(HANDLER_ID, reportId, handleDto("ban", 7)));

        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode());
        assertEquals("不能封禁管理员账号", e.getMessage());
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM user WHERE id = ?", Integer.class, adminAuthorId), "管理员不得被封");
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM report WHERE id = ?", Integer.class, reportId), "失败处置不得落状态");
    }

    @Test
    void missingTargetAuthorReturnsNotFound() {
        Long reportId = pendingReport(2, 987654321L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.handle(HANDLER_ID, reportId, handleDto("ban", 7)));

        assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM report WHERE id = ?", Integer.class, reportId), "定位失败不得改状态");
    }
}
