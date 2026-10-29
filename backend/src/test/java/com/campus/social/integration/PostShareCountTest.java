package com.campus.social.integration;

import com.campus.social.exception.BusinessException;
import com.campus.social.service.PostService;
import com.campus.social.vo.PostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 帖子分享计数（2026-10-27）。
 *
 * <p>产品口径：每成功分享一次 +1，自己分享也计，不做去重。
 * 同时守住可见性：只有 status=1 的帖子能分享，否则计数会成为探测未过审帖子的旁路。
 */
@SpringBootTest
@ActiveProfiles("local")
class PostShareCountTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private PostService postService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long postId;

    @BeforeEach
    void seed() {
        String username = "share-owner-" + SUFFIX;
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        authorId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?,?,1,0)",
                authorId, "share-test-" + SUFFIX);
        postId = jdbc.queryForObject("SELECT id FROM post WHERE content = ?", Long.class,
                "share-test-" + SUFFIX);
    }

    @AfterEach
    void cleanup() {
        if (postId != null) {
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        if (authorId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", authorId);
        }
    }

    @Test
    void everySuccessfulShareAddsOneAndDetailReturnsIt() {
        postService.share(postId);
        postService.share(postId);

        Integer stored = jdbc.queryForObject(
                "SELECT share_count FROM post WHERE id = ?", Integer.class, postId);
        assertEquals(2, stored, "两次分享应累计 2，不去重");

        PostVO vo = postService.detail(postId, 0L);
        assertEquals(2, vo.getShareCount(), "详情接口应下发 shareCount");
    }

    @Test
    void pendingPostCannotBeShared() {
        jdbc.update("UPDATE post SET status = 0 WHERE id = ?", postId);
        assertThrows(BusinessException.class, () -> postService.share(postId));
        Integer stored = jdbc.queryForObject(
                "SELECT share_count FROM post WHERE id = ?", Integer.class, postId);
        assertEquals(0, stored);
    }
}
