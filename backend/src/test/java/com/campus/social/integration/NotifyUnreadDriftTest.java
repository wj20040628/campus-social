package com.campus.social.integration;

import com.campus.social.mapper.UserMapper;
import com.campus.social.service.NotifyService;
import com.campus.social.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 未读通知计数漂移（2026-11-10，线上「红点点不掉」复现与防复发）。
 *
 * <p>根因：删帖会物理删除指向该帖的通知，其中可能有别人的未读行；
 * 历史版本删完不重算 {@code user.unread_notify_count}，且每日对账并未覆盖
 * 未读计数，于是计数永久大于实际，列表里却没有未读项——进入页面只清内存角标，
 * 刷新即复发，且「全部已读」按钮因列表无未读项而不显示。
 *
 * <p>本测试覆盖两条修复路径：删帖重算（根治）+ 未读接口自愈（兜底历史脏数据）。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class NotifyUnreadDriftTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final int NOTIFY_LIKE = 2;

    @Autowired
    private PostService postService;
    @Autowired
    private NotifyService notifyService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long postId;

    @BeforeEach
    void seed() {
        authorId = insertUser("notify-drift-" + SUFFIX);
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?, ?, 1, 0)",
                authorId, "未读漂移测试帖-" + SUFFIX);
        postId = jdbc.queryForObject(
                "SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, authorId);
        insertUnreadLikeNotify();
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    /** 模拟别人给作者点赞：写一条未读通知并同步 +1，与 PostNotifySupport.pushNotify 同口径 */
    private void insertUnreadLikeNotify() {
        jdbc.update("INSERT INTO notify (user_id, type, target_id, post_id, actor_id, is_read, is_deleted) "
                + "VALUES (?,?,?,?,?,0,0)", authorId, NOTIFY_LIKE, postId, postId, authorId);
        userMapper.adjustUnreadNotifyCount(authorId, 1);
    }

    private int storedUnreadCount() {
        return jdbc.queryForObject(
                "SELECT unread_notify_count FROM user WHERE id = ?", Integer.class, authorId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notify WHERE user_id = ?", authorId);
        jdbc.update("DELETE FROM feed_item WHERE post_id = ?", postId);
        jdbc.update("DELETE FROM post WHERE author_id = ?", authorId);
        jdbc.update("DELETE FROM user WHERE id = ?", authorId);
    }

    @Test
    void deletePost_recomputesUnreadCountOfRecipients() {
        assertEquals(1, notifyService.unreadCount(authorId), "删帖前作者应有 1 条未读");

        postService.delete(postId, authorId);

        assertEquals(0, storedUnreadCount(),
                "未读通知行被物理删除后，计数列必须同步重算（不能依赖次日对账或自愈接口）");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notify WHERE post_id = ?", Integer.class, postId),
                "指向该帖的通知应全部物理删除");
    }

    @Test
    void unreadCount_selfHealsStaleCounterWithoutUnreadRows() {
        // 模拟历史脏数据：通知行已被删（旧版本删帖不重算），计数列仍是 1
        jdbc.update("DELETE FROM notify WHERE user_id = ?", authorId);
        assertEquals(1, storedUnreadCount(), "前置：计数列仍为 1，且不存在未读行");

        assertEquals(0, notifyService.unreadCount(authorId), "自愈：无未读行时应返回 0");
        assertEquals(0, storedUnreadCount(), "自愈应同时把用户计数列清零，刷新后不再复发");
    }
}
