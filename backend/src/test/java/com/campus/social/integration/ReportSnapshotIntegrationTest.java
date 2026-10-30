package com.campus.social.integration;

import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.service.ReportAdminService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportAdminPageVO;
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
 * 举报时快照（2026-10-29）。
 *
 * <p>锁住两件最容易退化的事：
 * <ol>
 *   <li>举报提交时确实把正文/作者/媒体写进了 report.target_snapshot；</li>
 *   <li>作者事后删帖（物理删除）或删评论（软删除）后，管理端仍能从快照看到原文，
 *       而不是只剩「内容已删除或不存在」。</li>
 * </ol>
 * 自播种自清理，连本机 MySQL + Redis（local profile）。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ReportSnapshotIntegrationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private ReportService reportService;
    @Autowired
    private ReportAdminService reportAdminService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long reporterId;
    private Long postId;

    @BeforeEach
    void seed() {
        authorId = insertUser("snap-author-" + SUFFIX, "快照作者-" + SUFFIX);
        reporterId = insertUser("snap-reporter-" + SUFFIX, "快照举报人-" + SUFFIX);
    }

    private Long insertUser(String username, String nickname) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", nickname);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertPost(String content) {
        String media = "[\"/uploads/snapshot/" + SUFFIX + ".jpg\"]";
        jdbc.update("INSERT INTO post (author_id, content, media, status, is_deleted) VALUES (?,?,?,1,0)",
                authorId, content, media);
        return jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, authorId);
    }

    private Long submit(int targetType, long targetId) {
        ReportSubmitDTO d = new ReportSubmitDTO();
        d.setTargetType(targetType);
        d.setTargetId(targetId);
        d.setReasonType(2);
        d.setReasonText("快照测试");
        return reportService.submit(reporterId, d).getReportId();
    }

    private ReportVO findReport(Long reportId) {
        ReportAdminPageVO page = reportAdminService.list(0, false, null, 50);
        return page.getList().stream()
                .filter(r -> reportId.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待审列表里找不到刚提交的举报"));
    }

    @AfterEach
    void cleanup() {
        if (reporterId != null) {
            jdbc.update("DELETE FROM report WHERE reporter_id = ?", reporterId);
        }
        for (Long id : new Long[]{authorId, reporterId}) {
            if (id != null) {
                jdbc.update("DELETE FROM notify WHERE user_id = ? OR actor_id = ?", id, id);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        if (postId != null) {
            jdbc.update("DELETE FROM comment WHERE post_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
    }

    @Test
    void postSnapshotSurvivesHardDelete() {
        String content = "快照正文-" + SUFFIX;
        postId = insertPost(content);
        Long reportId = submit(2, postId);

        // 举报提交时就落了快照；此时原帖还在
        String snapshot = jdbc.queryForObject(
                "SELECT target_snapshot FROM report WHERE id = ?", String.class, reportId);
        assertNotNull(snapshot, "举报提交时应写入 target_snapshot");
        assertTrue(snapshot.contains(content), "快照应包含帖子正文");
        assertTrue(snapshot.contains("快照作者-" + SUFFIX), "快照应包含作者昵称");

        // 作者物理删帖：原内容没了，管理端要靠快照
        jdbc.update("DELETE FROM post WHERE id = ?", postId);

        ReportVO row = findReport(reportId);
        assertTrue(row.getTargetSummary().contains("举报时快照："), "摘要应回退到快照");
        assertTrue(row.getTargetSummary().contains(content), "摘要应包含快照正文");
        assertNotNull(row.getTargetSnapshotText(), "详情应返回渲染后的快照");
        assertTrue(row.getTargetSnapshotText().contains(content), "快照详情应包含正文");
        assertTrue(row.getTargetSnapshotText().contains("快照作者-" + SUFFIX), "快照详情应包含作者");
        assertTrue(row.getTargetSnapshotText().contains("媒体："), "快照详情应包含媒体");
        assertEquals(authorId, row.getTargetAuthorId(), "作者 id 应从快照回填");
        assertEquals("快照作者-" + SUFFIX, row.getTargetAuthorNickname(), "作者昵称应从快照回填");
    }

    @Test
    void commentSnapshotSurvivesSoftDelete() {
        postId = insertPost("宿主帖-" + SUFFIX);
        String content = "快照评论-" + SUFFIX;
        jdbc.update("INSERT INTO comment (post_id, user_id, content, status, level, is_deleted) "
                + "VALUES (?,?,?,1,1,0)", postId, authorId, content);
        Long commentId = jdbc.queryForObject(
                "SELECT id FROM comment WHERE post_id = ? ORDER BY id DESC LIMIT 1", Long.class, postId);
        Long reportId = submit(3, commentId);

        // 作者删评论：软删除，selectCommentContent 会查不到
        jdbc.update("UPDATE comment SET is_deleted = 1 WHERE id = ?", commentId);

        ReportVO row = findReport(reportId);
        assertTrue(row.getTargetSummary().contains("举报时快照："), "评论删除后摘要应回退到快照");
        assertTrue(row.getTargetSnapshotText().contains(content), "快照详情应包含评论正文");
        assertEquals(authorId, row.getTargetAuthorId(), "作者 id 应从快照回填");
    }
}
