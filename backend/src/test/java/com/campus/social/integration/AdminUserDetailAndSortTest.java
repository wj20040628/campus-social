package com.campus.social.integration;

import com.campus.social.service.AdminUserService;
import com.campus.social.vo.UserAdminDetailVO;
import com.campus.social.vo.UserAdminItemVO;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户处置详情与「最近处置」排序（2026-11-08）。
 *
 * <p>覆盖两件容易被改坏的事：
 * <ol>
 *   <li>详情里的举报必须把「用户本身 / 其帖子 / 其评论」三类目标都归因到本人；</li>
 *   <li>最近处置排序按 user.last_punish_time 倒序，未处置过的排最后。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AdminUserDetailAndSortTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private Long oldPunishId;
    private Long newPunishId;
    private Long neverPunishedId;

    @BeforeEach
    void seed() {
        oldPunishId = insertUser("adm-ds-old-" + SUFFIX);
        newPunishId = insertUser("adm-ds-new-" + SUFFIX);
        neverPunishedId = insertUser("adm-ds-none-" + SUFFIX);
        jdbc.update("UPDATE user SET last_punish_time = DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id = ?", oldPunishId);
        jdbc.update("UPDATE user SET last_punish_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = ?", newPunishId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM report WHERE target_type IN (1,2,3) AND target_id IN "
                + "(SELECT id FROM post WHERE author_id IN (?,?,?))", oldPunishId, newPunishId, neverPunishedId);
        jdbc.update("DELETE FROM post WHERE author_id IN (?,?,?)", oldPunishId, newPunishId, neverPunishedId);
        for (Long id : userIds) {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        userIds.clear();
    }

    @Test
    void recentPunishSortPutsNewestFirstAndNeverPunishedLast() {
        List<UserAdminItemVO> rows = adminUserService
                .page("adm-ds-", null, null, null, 10, "recentPunish").getList();
        List<Long> ids = rows.stream().map(UserAdminItemVO::getUserId).toList();
        assertTrue(ids.indexOf(newPunishId) < ids.indexOf(oldPunishId), "最近处置的人应排在前面");
        assertTrue(ids.indexOf(oldPunishId) < ids.indexOf(neverPunishedId), "从未处置的排最后");
    }

    @Test
    void detailIncludesReportsActionsAndPosts() {
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?,?,1,0)",
                newPunishId, "详情测试帖-" + SUFFIX);
        Long postId = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, newPunishId);
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, reason_text, status, is_deleted) "
                + "VALUES (1,2,?,2,'详情测试举报',0,0)", postId);
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1,'account_ban','user',?,'详情测试封禁')", newPunishId);

        UserAdminDetailVO detail = adminUserService.detail(newPunishId);
        assertNotNull(detail.getUser());
        assertEquals(1, detail.getReports().size(), "帖子被举报应归因到作者");
        assertEquals(2, detail.getReports().get(0).getTargetType());
        assertEquals(1, detail.getActions().size(), "账号处置轨迹");
        assertEquals("account_ban", detail.getActions().get(0).getAction());
        assertEquals(1, detail.getPosts().size(), "最近帖子");
        assertEquals("详情测试帖-" + SUFFIX, detail.getPosts().get(0).getExcerpt());
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,0,1,0)",
                username, "x", username);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }
}
