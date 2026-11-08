package com.campus.social.integration;

import com.campus.social.service.AdminOverviewService;
import com.campus.social.service.AdminUserService;
import com.campus.social.vo.AdminOverviewVO;
import com.campus.social.vo.UserAdminItemVO;
import com.campus.social.vo.UserAdminPageVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端用户处置的处置依据与管理总览（2026-11-07）。
 *
 * <p>覆盖三件容易回归的事：
 * <ol>
 *   <li>「全部」不混入已注销账号，但 status=2 仍能单独查到（审计要回查）；</li>
 *   <li>列表行的信誉分/被举报/成立/发帖/封禁次数与真实数据一致；</li>
 *   <li>总览的在册/已注销口径与待办聚合可用。</li>
 * </ol>
 * 自播种自清理，不依赖既有数据。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AdminUserStatsTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private AdminOverviewService adminOverviewService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();
    private final List<Long> reportIds = new ArrayList<>();

    private Long activeId;
    private Long cancelledId;
    private Long authorId;

    @BeforeEach
    void seed() {
        activeId = insertUser("adm-stats-active-" + SUFFIX, 0);
        cancelledId = insertUser("adm-stats-cancelled-" + SUFFIX, 2);
        authorId = insertUser("adm-stats-author-" + SUFFIX, 0);
    }

    @AfterEach
    void clean() {
        for (Long reportId : reportIds) {
            jdbc.update("DELETE FROM report WHERE id = ?", reportId);
        }
        for (Long postId : postIds) {
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", userId);
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
        userIds.clear();
        postIds.clear();
        reportIds.clear();
    }

    /** 已注销账号默认不出现；显式传 status=2 才返回。 */
    @Test
    void defaultListExcludesCancelledButFilterFindsThem() {
        UserAdminPageVO all = adminUserService.page("adm-stats-", null, null, null, 50);
        List<Long> allIds = all.getList().stream().map(UserAdminItemVO::getUserId).toList();
        assertTrue(allIds.contains(activeId), "在册用户应出现在全部");
        assertFalse(allIds.contains(cancelledId), "已注销账号不得混进全部");
        assertEquals(allIds.size(), all.getTotal().intValue(), "total 与列表同一套 WHERE");

        UserAdminPageVO cancelled = adminUserService.page("adm-stats-", 2, null, null, 50);
        List<Long> cancelledIds = cancelled.getList().stream().map(UserAdminItemVO::getUserId).toList();
        assertTrue(cancelledIds.contains(cancelledId), "已注销筛选必须能回查到");
        assertFalse(cancelledIds.contains(activeId), "已注销筛选只返回 status=2");
    }

    /** 行内五项处置依据必须与真实数据一致（同一页批量统计，不靠逐行口径）。 */
    @Test
    void rowStatsMatchRealData() {
        Long postId = insertPost(authorId, "统计测试帖-" + SUFFIX);
        Long otherPostId = insertPost(authorId, "统计测试帖2-" + SUFFIX);
        insertUpheldReport(2, postId);
        // 信誉分认 audit_log 的 report_upheld（举报处置时写入），不是 report.status；
        // 两个都插，模拟真实处置链路
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'report_upheld', 'user', ?, '统计测试')", authorId);
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'account_ban', 'user', ?, '统计测试')", authorId);

        UserAdminItemVO row = adminUserService.page("adm-stats-author-", null, null, null, 10)
                .getList().stream()
                .filter(u -> u.getUserId().equals(authorId))
                .findFirst().orElseThrow();

        assertEquals(2, row.getPostCount(), "公开帖数");
        assertEquals(1, row.getReportCount(), "被举报次数（帖子目标归因到作者）");
        assertEquals(1, row.getUpheldCount(), "成立次数");
        assertEquals(1, row.getBanCount(), "累计封禁次数");
        // 60 基准 + 2 条过审帖 - 1 次成立举报 x20 - 1 次封禁 x40 = 2
        assertEquals(2, row.getCreditScore(), "信誉分与 ReviewTrustService 同公式");
        assertNotNull(otherPostId);
    }

    /** 总览四组计数可读，且待办与 pending-summary 同源。 */
    @Test
    void overviewCountsAreConsistent() {
        AdminOverviewVO vo = adminOverviewService.overview();
        assertNotNull(vo.getPending(), "待办必须复用 pending-summary");
        assertNotNull(vo.getPending().getReportPending());
        assertTrue(vo.getActiveUsers() >= 1, "在册用户至少包含本用例作者");
        assertTrue(vo.getCancelledUsers() >= 1, "已注销用户至少包含本用例用户");
        assertTrue(vo.getPosts() >= 0);
        assertTrue(vo.getTodayNewUsers() >= 0);
    }

    private Long insertUser(String username, int status) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,?,0,1,0,1,0)",
                username, "x", username, status);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }

    private Long insertPost(Long authorId, String content) {
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?,?,1,0)",
                authorId, content);
        Long id = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, authorId);
        postIds.add(id);
        return id;
    }

    private void insertUpheldReport(int targetType, Long targetId) {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (1,?,?,1,1,0)", targetType, targetId);
        Long id = jdbc.queryForObject("SELECT id FROM report ORDER BY id DESC LIMIT 1", Long.class);
        reportIds.add(id);
    }
}
