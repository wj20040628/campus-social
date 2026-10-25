package com.campus.social.integration;

import com.campus.social.common.CursorPage;
import com.campus.social.controller.UserController;
import com.campus.social.service.impl.PostFeedQueryService;
import com.campus.social.vo.PostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 树洞 feed 过滤 + 邮件摘要偏好合并写回归（2026-10-25）。
 *
 * <p>合并写是本测试的重点：user.notify_pref 现在有两个段（站内 disabled /
 * 邮件 emailDigest），两个设置页各写各的——任何一个用整列覆盖都会把对方
 * 刚保存的抹掉。此前 PUT /notify-pref 就是覆盖写，若不改造，用户调一下
 * 免打扰就清空邮件订阅。
 */
@SpringBootTest
@ActiveProfiles("local")
class DigestAndHoleFeedTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long AUTHOR = 1L;

    @Autowired
    private PostFeedQueryService feedQuery;
    @Autowired
    private UserController userController;
    @Autowired
    private JdbcTemplate jdbc;

    private Long anonA;
    private Long anonB;
    private Long plain;
    private Long digestUserId;

    @BeforeEach
    void seed() {
        anonA = insertPost(1);
        anonB = insertPost(1);
        plain = insertPost(0);

        digestUserId = insertUser("digest-" + SUFFIX);
    }

    private Long insertPost(int anonymous) {
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted, is_anonymous) "
                + "VALUES (?, ?, 1, 0, ?)", AUTHOR, "dh" + SUFFIX, anonymous);
        return jdbc.queryForObject("SELECT id FROM post WHERE content = ? ORDER BY id DESC LIMIT 1",
                Long.class, "dh" + SUFFIX);
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                username, "x", username, username + "@campus.test");
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM post WHERE content = ?", "dh" + SUFFIX);
        if (digestUserId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", digestUserId);
        }
    }

    private long countMine(CursorPage<PostVO> page) {
        return page.getList().stream().filter(p ->
                p.getContent() != null && p.getContent().equals("dh" + SUFFIX)).count();
    }

    @Test
    void anonymousFeedFilterReturnsOnlyHolePosts() {
        CursorPage<PostVO> hole = feedQuery.feed(0L, null, 20, "time", "desc", null, true);
        CursorPage<PostVO> all = feedQuery.feed(0L, null, 20, "time", "desc", null, null);

        assertEquals(2, countMine(hole), "树洞过滤只回匿名帖");
        assertTrue(hole.getList().stream().allMatch(PostVO::isAnonymous));
        assertEquals(3, countMine(all), "不带过滤回全部(含匿名)");
        assertFalse(all.getList().stream().allMatch(PostVO::isAnonymous));
    }

    @Test
    void prefWritesMergeInsteadOfOverwrite() {
        // 1. 开邮件摘要(订 chat/like)
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute("userId", digestUserId);
        userController.setEmailDigest("{\"enabled\":true,\"types\":[\"chat\",\"like\"]}", req);

        // 2. 再关站内评论通知(旧入口)——覆盖写会把 emailDigest 抹掉
        userController.setNotifyPref("{\"disabled\":[3]}", req);

        // 3. 两边各自活着
        var digest = userController.getEmailDigest(req).getData();
        assertTrue(digest.getEnabled(), "邮件订阅应保留(合并写)");
        assertEquals(java.util.List.of("chat", "like"), digest.getTypes());

        String pref = jdbc.queryForObject(
                "SELECT notify_pref FROM user WHERE id = ?", String.class, digestUserId);
        assertTrue(pref.contains("\"disabled\":[3]"), "站内免打扰应保留(合并写)");
    }
}
