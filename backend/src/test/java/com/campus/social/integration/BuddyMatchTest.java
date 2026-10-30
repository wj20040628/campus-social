package com.campus.social.integration;

import com.campus.social.service.impl.BuddyMatchService;
import com.campus.social.vo.RecommendUserListVO;
import com.campus.social.vo.RecommendUserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.temporal.WeekFields;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本周搭子回归（2026-10-25，配 selectWeeklyBuddies / BuddyMatchService）。
 *
 * <p>断言三条口径差异（相对 /onboarding/recommend-users）：
 * 无共同标签的人不进名单；「拉黑了我的人」不进名单（推荐关注只单向过滤）；
 * 结果非空时 emptyHint 为 null。数据全部自播种自清理（CI 只加载 schema）。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class BuddyMatchTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private BuddyMatchService buddyMatchService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private Long meId;
    private Long buddyId;
    private Long noTagUserId;
    private Long blockedMeId;
    private Long tagId;
    private String weekCacheKey;

    @BeforeEach
    void seed() {
        tagId = insertTag();
        meId = insertUser("buddy-me-" + SUFFIX, 0);
        // 命中者也不填校园信息：搭子推荐不再以 verify_status=1 为前置
        buddyId = insertUser("buddy-hit-" + SUFFIX, 0);
        noTagUserId = insertUser("buddy-notag-" + SUFFIX, 1);
        blockedMeId = insertUser("buddy-blockedme-" + SUFFIX, 1);

        // 我和命中者共享标签;无标签者不插;拉黑我的人也共享标签(测双向过滤)
        jdbc.update("INSERT INTO user_tag (user_id, tag_id, is_deleted) VALUES (?,?,0)", meId, tagId);
        jdbc.update("INSERT INTO user_tag (user_id, tag_id, is_deleted) VALUES (?,?,0)", buddyId, tagId);
        jdbc.update("INSERT INTO user_tag (user_id, tag_id, is_deleted) VALUES (?,?,0)", blockedMeId, tagId);
        // relation type=3 拉黑: blockedMe -> me(他拉黑我,方向不是我拉黑他)
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,3,0)",
                blockedMeId, meId);

        weekCacheKey = "buddy:week:" + meId + ":" + currentWeek();
        redis.delete(weekCacheKey);
    }

    private Long insertTag() {
        jdbc.update("INSERT INTO interest_tag (name, category, sort, use_count, is_deleted) VALUES (?,?,10,0,0)",
                "搭子回归" + SUFFIX, 1);
        return jdbc.queryForObject("SELECT id FROM interest_tag WHERE name = ?",
                Long.class, "搭子回归" + SUFFIX);
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private String currentWeek() {
        WeekFields wf = WeekFields.ISO;
        LocalDate now = LocalDate.now();
        return now.get(wf.weekBasedYear()) + "-W" + now.get(wf.weekOfWeekBasedYear());
    }

    @AfterEach
    void cleanup() {
        for (Long uid : new Long[]{meId, buddyId, noTagUserId, blockedMeId}) {
            if (uid != null) {
                jdbc.update("DELETE FROM user_tag WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", uid, uid);
                jdbc.update("DELETE FROM user WHERE id = ?", uid);
            }
        }
        if (tagId != null) {
            jdbc.update("DELETE FROM interest_tag WHERE id = ?", tagId);
        }
        if (weekCacheKey != null) {
            redis.delete(weekCacheKey);
        }
    }

    @Test
    void weeklyBuddiesMatchesBySharedTagAndFiltersBlacklist() {
        RecommendUserListVO vo = buddyMatchService.weeklyBuddies(meId, 6);
        assertNull(vo.getEmptyHint(), "有共同标签的搭子,不该给空态文案");

        RecommendUserVO hit = vo.getUsers().stream()
                .filter(u -> u.getId().equals(buddyId)).findFirst().orElse(null);
        assertTrue(hit != null, "共享标签且开着陌生人消息的人应被推荐（不看校园信息）");
        assertTrue(hit.getReason().contains("都喜欢 1 个标签"), "理由应标签主导: " + hit.getReason());

        assertFalse(vo.getUsers().stream().anyMatch(u -> u.getId().equals(noTagUserId)),
                "无共同标签的人不进搭子名单");
        assertFalse(vo.getUsers().stream().anyMatch(u -> u.getId().equals(blockedMeId)),
                "拉黑了我的人不进搭子名单(双向过滤)");
    }
}
