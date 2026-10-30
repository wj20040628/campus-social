package com.campus.social.integration;

import com.campus.social.controller.UserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件摘要偏好合并写 + 审核中帖子/评论可见性回归。
 *
 * <p>合并写是本测试的重点：user.notify_pref 现在有两个段（站内 disabled /
 * 邮件 emailDigest），两个设置页各写各的——任何一个用整列覆盖都会把对方
 * 刚保存的抹掉。此前 PUT /notify-pref 就是覆盖写，若不改造，用户调一下
 * 免打扰就清空邮件订阅。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class DigestAndHoleFeedTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final long AUTHOR = 1L;
    /** 审核中帖子的唯一内容标记：带 SUFFIX，cleanup 才能精确删掉本次插入的行 */
    private static final String PENDING = "pending-hide-" + SUFFIX;

    @Autowired
    private com.campus.social.service.PostService postService;
    @Autowired
    private UserController userController;
    @Autowired
    private JdbcTemplate jdbc;

    private Long digestUserId;

    @BeforeEach
    void seed() {
        digestUserId = insertUser("digest-" + SUFFIX);
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                username, "x", username, username + "@campus.test");
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        // 先删评论再删帖：pending-hide 的帖子与评论都不能残留
        jdbc.update("DELETE FROM comment WHERE content = ?", "pc" + SUFFIX);
        jdbc.update("DELETE FROM post WHERE content = ?", PENDING);
        if (digestUserId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", digestUserId);
        }
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

    @Test
    void pendingPostHiddenFromGuestAndOthers() {
        // 2026-10-25 审计修回归(外部审查抓出):「审核中」帖子对游客/无关用户必须 404
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) "
                + "VALUES (?, ?, 0, 0)", AUTHOR, PENDING);
        Long pendingId = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? AND content = ? ORDER BY id DESC LIMIT 1",
                Long.class, AUTHOR, PENDING);

        try {
            postService.detail(pendingId, 0L); // 游客
            org.junit.jupiter.api.Assertions.fail("游客不应读到审核中的帖子");
        } catch (com.campus.social.exception.BusinessException e) {
            assertEquals(com.campus.social.common.ResultCode.POST_NOT_FOUND.getCode(), e.getCode());
        }
        try {
            postService.detail(pendingId, 999L); // 无关登录用户
            org.junit.jupiter.api.Assertions.fail("无关用户不应读到审核中的帖子");
        } catch (com.campus.social.exception.BusinessException e) {
            assertEquals(com.campus.social.common.ResultCode.POST_NOT_FOUND.getCode(), e.getCode());
        }
        // 作者本人可见(通知跳详情的路径必须仍通)
        var self = postService.detail(pendingId, AUTHOR);
        assertEquals(PENDING, self.getContent());

        /*
         * 2026-10-30 审计修：评论列表曾绕过帖子状态，下架/退回审核的帖子
         * 详情已 404，评论却能按顺序 id 枚举。评论必须与详情同口径。
         */
        jdbc.update("INSERT INTO comment (post_id, user_id, parent_id, level, content, status, is_deleted) "
                + "VALUES (?, ?, 0, 1, ?, 1, 0)", pendingId, AUTHOR, "pc" + SUFFIX);
        for (long viewer : new long[]{0L, 999L}) {
            try {
                postService.comments(pendingId, null, 20, viewer);
                org.junit.jupiter.api.Assertions.fail("非作者不应读到审核中帖子的评论, viewer=" + viewer);
            } catch (com.campus.social.exception.BusinessException e) {
                assertEquals(com.campus.social.common.ResultCode.POST_NOT_FOUND.getCode(), e.getCode());
            }
        }
        assertEquals(1, postService.comments(pendingId, null, 20, AUTHOR).getList().size(),
                "作者本人仍可看到自己帖子下的评论");
    }
}
