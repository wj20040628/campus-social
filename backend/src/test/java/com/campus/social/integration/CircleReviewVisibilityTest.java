package com.campus.social.integration;

import com.campus.social.common.CursorPage;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.ReviewService;
import com.campus.social.vo.ReviewTaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圈内待审任务的可见性（2026-11-08 修）。
 *
 * <p>回归的是这个组合故障：圈主账号同时是平台管理员时，
 * 旧 {@code /review/pending} 先按管理员身份分流进平台队列，
 * 而平台队列又按设计排除 24h 内的圈内任务；圈管理抽屉同样拿不到。
 * 结果帖子卡在「审核中」，圈主与管理员两边都看不到。
 *
 * <p>新口径：
 * <ul>
 *   <li>带 circleId → 该圈完整待审队列（管理员或圈主，不限窗口）；</li>
 *   <li>管理员不带 circleId → 全平台待审（含圈内任务）；</li>
 *   <li>普通圈主不带 circleId → 自己圈内未升级任务。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class CircleReviewVisibilityTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long circleId;
    private Long taskId;
    private Long postId;
    private Long ownerId;
    private Long authorId;
    private Long strangerId;

    @AfterEach
    void clean() {
        if (taskId != null) {
            jdbc.update("DELETE FROM review_task WHERE id = ?", taskId);
        }
        if (postId != null) {
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        if (circleId != null) {
            jdbc.update("DELETE FROM circle WHERE id = ?", circleId);
        }
        for (Long id : new Long[]{ownerId, authorId, strangerId}) {
            if (id != null) {
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
    }

    @Test
    void adminOwnerSeesTaskInCircleQueueAndPlatformQueue() {
        seed(1);
        CursorPage<ReviewTaskVO> circleQueue = reviewService.pending(ownerId, circleId, null, 20);
        assertEquals(1, circleQueue.getList().size(), "圈主(同时是管理员)必须能在圈内队列看到待审");
        assertEquals(circleId, circleQueue.getList().get(0).getCircleId(), "队列行要标出来源圈子");
        assertEquals(1, circleQueue.getTotal().intValue(), "total 与列表同一 WHERE");

        CursorPage<ReviewTaskVO> platform = reviewService.pending(ownerId, null, null, 20);
        assertTrue(platform.getList().stream().anyMatch(t -> t.getId().equals(taskId)),
                "平台队列必须包含 24h 内的圈内任务，不能再让它隐身");
    }

    @Test
    void plainOwnerSeesOwnCircleTaskButStrangerCannot() {
        seed(0);
        CursorPage<ReviewTaskVO> circleQueue = reviewService.pending(ownerId, circleId, null, 20);
        assertEquals(1, circleQueue.getList().size(), "圈主能看自己圈的待审");

        CursorPage<ReviewTaskVO> ownerQueue = reviewService.pending(ownerId, null, null, 20);
        assertTrue(ownerQueue.getList().stream().anyMatch(t -> t.getId().equals(taskId)),
                "升级窗口内的任务仍在圈主个人队列");

        assertThrows(BusinessException.class,
                () -> reviewService.pending(strangerId, circleId, null, 20),
                "非圈主/非管理员不能查看该圈队列");
    }

    /** @param ownerRole 1=平台管理员，0=普通用户 */
    private void seed(int ownerRole) {
        ownerId = insertUser("cvis-owner-" + SUFFIX, ownerRole);
        authorId = insertUser("cvis-author-" + SUFFIX, 0);
        strangerId = insertUser("cvis-stranger-" + SUFFIX, 0);

        jdbc.update("INSERT INTO circle (name, owner_id, status, is_deleted) VALUES (?,?,0,0)",
                "cvis-circle-" + SUFFIX, ownerId);
        circleId = jdbc.queryForObject("SELECT id FROM circle WHERE name = ?", Long.class,
                "cvis-circle-" + SUFFIX);

        jdbc.update("INSERT INTO post (author_id, circle_id, content, status, is_deleted) "
                + "VALUES (?,?,?,0,0)", authorId, circleId, "圈内待审帖-" + SUFFIX);
        postId = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, authorId);

        jdbc.update("INSERT INTO review_task (post_id, status, hit_words, is_deleted) "
                + "VALUES (?,0,?,0)", postId, "[测试词]");
        taskId = jdbc.queryForObject("SELECT id FROM review_task WHERE post_id = ?", Long.class, postId);
    }

    private Long insertUser(String username, int role) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,?,1,0,1,0)",
                username, "x", username, role);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }
}
