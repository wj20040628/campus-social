package com.campus.social.integration;

import com.campus.social.dto.CreatePostDTO;
import com.campus.social.service.PostService;
import com.campus.social.vo.PostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑帖子话题(2026-11-12)。
 *
 * <p>回归三个真实缺陷：
 * <ol>
 *   <li>编辑判重拿「旧话题 id」比「新话题名」，永远判成有改动；</li>
 *   <li>软删后重绑同一话题撞 uk_pt，编辑直接 500；</li>
 *   <li>新建话题 post_count 从 0 起步，首帖不计数。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class PostEditTopicTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private PostService postService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long postId;
    private String topicName;

    @BeforeEach
    void seed() {
        String username = "edit-topic-" + SUFFIX;
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, "x", username);
        authorId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);

        topicName = "编辑话题" + SUFFIX;
        CreatePostDTO dto = new CreatePostDTO();
        dto.setContent("edit-topic-test-" + SUFFIX);
        dto.setTopics(List.of(topicName));
        postId = postService.create(authorId, dto);
    }

    @AfterEach
    void cleanup() {
        if (postId != null) {
            Long topicId = jdbc.queryForObject(
                    "SELECT topic_id FROM post_topic WHERE post_id = ? LIMIT 1", Long.class, postId);
            jdbc.update("DELETE FROM post_topic WHERE post_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
            if (topicId != null) {
                jdbc.update("DELETE FROM topic WHERE id = ?", topicId);
            }
        }
        if (authorId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", authorId);
        }
    }

    @Test
    void newTopicCountsFirstPost() {
        Integer count = jdbc.queryForObject(
                "SELECT post_count FROM topic WHERE name = ?", Integer.class, topicName);
        assertEquals(1, count, "新话题的第一帖应从 1 起算");
    }

    @Test
    void editContentKeepingSameTopicsDoesNotThrow() {
        CreatePostDTO dto = new CreatePostDTO();
        dto.setContent("edit-topic-test-" + SUFFIX + "-v2");
        dto.setTopics(List.of(topicName));

        // 旧实现会因 id/name 误判 + uk_pt 冲突抛 DuplicateKeyException
        postService.edit(postId, dto, authorId);

        PostVO vo = postService.detail(postId, 0L);
        assertTrue(vo.getContent().endsWith("-v2"));
        assertEquals(List.of(topicName), vo.getTopics(), "话题应保持不变");
        Integer count = jdbc.queryForObject(
                "SELECT post_count FROM topic WHERE name = ?", Integer.class, topicName);
        assertEquals(1, count, "编辑同一话题不应把计数刷成 2 或 0");
    }

    @Test
    void editCanRemoveAndReAddSameTopic() {
        CreatePostDTO empty = new CreatePostDTO();
        empty.setContent("edit-topic-test-" + SUFFIX);
        empty.setTopics(List.of());
        postService.edit(postId, empty, authorId);
        assertEquals(List.of(), postService.detail(postId, 0L).getTopics(), "空数组应删光话题");

        CreatePostDTO again = new CreatePostDTO();
        again.setContent("edit-topic-test-" + SUFFIX);
        again.setTopics(List.of(topicName));
        postService.edit(postId, again, authorId);
        assertEquals(List.of(topicName), postService.detail(postId, 0L).getTopics(),
                "删掉再加回同一话题不应撞软删残留");
    }
}
