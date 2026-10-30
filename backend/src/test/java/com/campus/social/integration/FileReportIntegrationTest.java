package com.campus.social.integration;

import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.service.ReportAdminService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportAdminPageVO;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;
import com.campus.social.vo.ReportVO;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件举报闭环（2026-10-27）。
 *
 * <p>覆盖三处容易「各写各的、拼起来不通」的接缝：
 * <ul>
 *   <li>DTO 校验上限必须跟着枚举放宽：枚举加了文件、@Max 没加，会在参数绑定阶段 400</li>
 *   <li>管理端列表要知道被举报文件的标题与作者，否则管理员只能盲判</li>
 *   <li>举报成立必须把文件 status 置 3（下架），而不只是封作者</li>
 * </ul>
 * 自清理自播种。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class FileReportIntegrationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final String TITLE = "举报测试文件-" + SUFFIX;

    @Autowired
    private ReportService reportService;
    @Autowired
    private ReportAdminService reportAdminService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uploaderId;
    private Long reporterId;
    private Long fileId;

    @BeforeEach
    void seed() {
        uploaderId = insertUser("file-report-owner-" + SUFFIX);
        reporterId = insertUser("file-report-user-" + SUFFIX);
        String objectKey = "files/test/" + SUFFIX + ".pdf";
        jdbc.update("INSERT INTO file_asset (user_id, title, description, category, file_name, object_key, "
                        + "ext, mime_type, size_bytes, status) VALUES (?,?,?,?,?,?,?,?,?,1)",
                uploaderId, TITLE, "举报链路测试", "template", "test.pdf", objectKey,
                "pdf", "application/pdf", 100L);
        fileId = jdbc.queryForObject("SELECT id FROM file_asset WHERE object_key = ?", Long.class, objectKey);
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        if (fileId != null) {
            jdbc.update("DELETE FROM report WHERE target_type = 7 AND target_id = ?", fileId);
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'file' AND target_id = ?", fileId);
            jdbc.update("DELETE FROM file_asset WHERE id = ?", fileId);
        }
        for (Long id : new Long[]{uploaderId, reporterId}) {
            if (id != null) {
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM notify WHERE user_id = ? OR actor_id = ?", id, id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
    }

    @Test
    void fileReportSubmitListAndTakedownWork() {
        /*
         * 直接验 DTO 约束：controller 的 @Valid 走的就是这个 Validator。
         * 7/8/9 必须通过；10 必须被拒 —— 后者防「以后再加类型又忘了放宽上限」。
         */
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ReportSubmitDTO valid = dto(7);
            assertTrue(factory.getValidator().validate(valid).isEmpty(), "targetType=7 应通过校验");
            ReportSubmitDTO groupMessage = dto(8);
            assertTrue(factory.getValidator().validate(groupMessage).isEmpty(), "targetType=8 应通过校验");
            ReportSubmitDTO circle = dto(9);
            assertTrue(factory.getValidator().validate(circle).isEmpty(), "targetType=9 应通过校验");
            ReportSubmitDTO tooHigh = dto(10);
            assertFalse(factory.getValidator().validate(tooHigh).isEmpty(), "targetType=10 应被拒绝");
        }

        ReportSubmitVO submit = reportService.submit(reporterId, dto(7));
        assertNotNull(submit.getReportId());
        assertFalse(submit.getDuplicated());
        assertEquals(0, submit.getStatus());

        ReportAdminPageVO page = reportAdminService.list(0, false, null, 20);
        ReportVO row = page.getList().stream()
                .filter(r -> submit.getReportId().equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待审列表里找不到刚提交的文件举报"));
        assertEquals(7, row.getTargetType());
        assertTrue(row.getTargetSummary().contains(TITLE), "摘要应带文件标题");
        assertEquals(uploaderId, row.getTargetAuthorId());
        assertEquals("file-report-owner-" + SUFFIX, row.getTargetAuthorNickname());

        ReportHandleDTO handle = new ReportHandleDTO();
        handle.setAction("ban");
        handle.setBanDays(7);
        handle.setHandleNote("T" + SUFFIX);
        ReportHandleVO handled = reportService.handle(1L, submit.getReportId(), handle);
        assertEquals(1, handled.getStatus());

        Integer fileStatus = jdbc.queryForObject(
                "SELECT status FROM file_asset WHERE id = ?", Integer.class, fileId);
        assertEquals(3, fileStatus, "举报成立后文件应下架(status=3)");
        Integer upheld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'report_upheld' "
                        + "AND target_type = 'user' AND target_id = ?", Integer.class, uploaderId);
        assertEquals(1, upheld, "成立举报应给作者记一次 report_upheld");
    }

    private ReportSubmitDTO dto(int targetType) {
        ReportSubmitDTO dto = new ReportSubmitDTO();
        dto.setTargetType(targetType);
        dto.setTargetId(fileId);
        dto.setReasonType(5);
        dto.setReasonText("含个人信息");
        return dto;
    }
}
