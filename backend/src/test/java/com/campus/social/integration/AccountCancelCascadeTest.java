package com.campus.social.integration;

import com.campus.social.service.AccountCancelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号注销级联清退（1.13.9）：
 * 群/圈成员关系、群主/圈主移交或解散、活动报名回退、帖子留痕保留。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AccountCancelCascadeTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 1_000_000);
    private static final String EMAIL = "cancel-cascade-" + SUFFIX + "@campus.test";
    private static final String CODE = "246810";

    @Autowired
    private AccountCancelService cancelService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> circleIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();

    private long cancelUser;
    private long memberA;
    private long memberB;
    private long memberC;
    private long circleAdmin;
    private long bannedAdmin;
    private long memberD;
    private long otherOwner;
    private long cancelledMember;
    private long frozenMember;
    private long groupTransfer;
    private long groupDisband;
    private long groupMemberOnly;
    private long circleTransfer;
    private long circleTransferBanned;
    private long circleDisband;
    private long circleMemberOnly;
    private long activityId;

    @BeforeEach
    void seed() {
        cancelUser = user("cancel", 0, 4, EMAIL);
        memberA = user("ma", 0, 0, null);
        memberB = user("mb", 0, 0, null);
        memberC = user("mc", 0, 1, null);
        circleAdmin = user("ca", 0, 1, null);
        bannedAdmin = user("ba", 0, 1, null);
        memberD = user("md", 0, 1, null);
        otherOwner = user("oo", 0, 1, null);
        cancelledMember = user("cm", 2, 0, null);
        frozenMember = user("fm", 1, 1, null);

        // 群 1：注销用户是群主，memberA/memberB 正常 → 移交最早加入的 memberA
        groupTransfer = group("移交群", cancelUser, 3);
        groupMember(groupTransfer, cancelUser, 1);
        groupMember(groupTransfer, memberA, 2);
        groupMember(groupTransfer, memberB, 2);
        jdbc.update("INSERT INTO group_read (group_id, user_id, last_read_msg_id) VALUES (?,?,0)",
                groupTransfer, cancelUser);
        jdbc.update("INSERT INTO group_message (group_id, sender_id, content) VALUES (?,?,?)",
                groupTransfer, memberA, "群移交后仍保留的历史消息");

        // 群 2：注销用户是群主，唯一成员已注销 → 解散并清消息
        groupDisband = group("解散群", cancelUser, 2);
        groupMember(groupDisband, cancelUser, 1);
        groupMember(groupDisband, cancelledMember, 2);
        jdbc.update("INSERT INTO group_read (group_id, user_id, last_read_msg_id) VALUES (?,?,0)",
                groupDisband, cancelUser);
        jdbc.update("INSERT INTO group_message (group_id, sender_id, content) VALUES (?,?,?)",
                groupDisband, cancelUser, "解散群将被物理清理的消息");

        // 群 3：注销用户是普通成员 → 退出
        groupMemberOnly = group("退出群", otherOwner, 2);
        groupMember(groupMemberOnly, otherOwner, 1);
        groupMember(groupMemberOnly, cancelUser, 2);

        // 圈 1：注销用户是圈主，普通成员先入、圈管理后入 → 优先移交圈管理
        circleTransfer = circle("移交圈", cancelUser, 3);
        circleMember(circleTransfer, cancelUser, 1);
        circleMember(circleTransfer, memberC, 3);
        circleMember(circleTransfer, circleAdmin, 2);

        // 圈 1b：圈管理被圈内封禁，不能接圈 → 移交给普通成员 memberD
        circleTransferBanned = circle("禁言圈", cancelUser, 3);
        circleMember(circleTransferBanned, cancelUser, 1);
        circleMember(circleTransferBanned, bannedAdmin, 2);
        circleMember(circleTransferBanned, memberD, 3);
        jdbc.update("UPDATE circle_member SET ban_status = 1 WHERE circle_id = ? AND user_id = ?",
                circleTransferBanned, bannedAdmin);

        // 圈 2：注销用户是圈主，唯一成员冻结 → 解散并下架圈内帖子
        circleDisband = circle("解散圈", cancelUser, 2);
        circleMember(circleDisband, cancelUser, 1);
        circleMember(circleDisband, frozenMember, 3);
        post(cancelUser, circleDisband, "圈解散后应下架的帖子", 1);

        // 圈 3：注销用户是普通成员 → 退出
        circleMemberOnly = circle("退出圈", otherOwner, 2);
        circleMember(circleMemberOnly, otherOwner, 1);
        circleMember(circleMemberOnly, cancelUser, 3);

        // 活动已满：注销用户报名占着唯一名额 → 退报并回补
        activityId = activity("满员活动", 1, 1, 1);
        jdbc.update("INSERT INTO activity_signup (activity_id, user_id) VALUES (?,?)", activityId, cancelUser);

        // 内容留痕：不挂圈的帖子注销后保留
        post(cancelUser, null, "注销后保留的帖子", 1);

        redis.opsForValue().set("code:cancel:" + EMAIL, CODE);
    }

    @AfterEach
    void cleanup() {
        redis.delete("code:cancel:" + EMAIL);
        redis.delete("code:fail:cancel:" + EMAIL);
        for (Long activity : activityIds) {
            jdbc.update("DELETE FROM activity_signup WHERE activity_id = ?", activity);
            jdbc.update("DELETE FROM activity WHERE id = ?", activity);
        }
        for (Long post : postIds) {
            jdbc.update("DELETE FROM post WHERE id = ?", post);
        }
        for (Long circle : circleIds) {
            jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", circle);
            jdbc.update("DELETE FROM circle WHERE id = ?", circle);
        }
        for (Long group : groupIds) {
            jdbc.update("DELETE FROM group_message WHERE group_id = ?", group);
            jdbc.update("DELETE FROM group_read WHERE group_id = ?", group);
            jdbc.update("DELETE FROM group_member WHERE group_id = ?", group);
            jdbc.update("DELETE FROM `group` WHERE id = ?", group);
        }
        for (Long id : userIds) {
            redis.delete("refresh:" + id);
            redis.delete("user:status:" + id);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", id);
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        userIds.clear();
        groupIds.clear();
        circleIds.clear();
        activityIds.clear();
        postIds.clear();
    }

    @Test
    void cancelCleansGroupCircleAndActivityRelations() {
        cancelService.cancel(cancelUser, CODE);

        // 群主注销：有正常成员 → 移交最早加入者
        Map<String, Object> g1 = jdbc.queryForMap(
                "SELECT owner_id, member_count, is_deleted FROM `group` WHERE id = ?", groupTransfer);
        assertEquals(memberA, ((Number) g1.get("owner_id")).longValue());
        assertEquals(2, ((Number) g1.get("member_count")).intValue());
        assertEquals(0, ((Number) g1.get("is_deleted")).intValue());
        assertEquals(1, groupRole(groupTransfer, memberA));
        assertEquals(0, count("SELECT COUNT(*) FROM group_member WHERE group_id = ? AND user_id = ?",
                groupTransfer, cancelUser));
        assertEquals(0, count("SELECT COUNT(*) FROM group_read WHERE group_id = ? AND user_id = ?",
                groupTransfer, cancelUser));

        // 群主注销：没有正常成员 → 解散并清群消息
        Map<String, Object> g2 = jdbc.queryForMap(
                "SELECT is_deleted FROM `group` WHERE id = ?", groupDisband);
        assertEquals(1, ((Number) g2.get("is_deleted")).intValue());
        assertEquals(0, count("SELECT COUNT(*) FROM group_member WHERE group_id = ?", groupDisband));
        assertEquals(0, count("SELECT COUNT(*) FROM group_read WHERE group_id = ?", groupDisband));
        assertEquals(0, count("SELECT COUNT(*) FROM group_message WHERE group_id = ?", groupDisband));

        // 普通成员注销：只退出，不动群主
        Map<String, Object> g3 = jdbc.queryForMap(
                "SELECT owner_id, member_count FROM `group` WHERE id = ?", groupMemberOnly);
        assertEquals(otherOwner, ((Number) g3.get("owner_id")).longValue());
        assertEquals(1, ((Number) g3.get("member_count")).intValue());
        assertEquals(0, count("SELECT COUNT(*) FROM group_member WHERE group_id = ? AND user_id = ?",
                groupMemberOnly, cancelUser));

        // 圈主注销：圈管理与普通成员都可接时，优先圈管理
        Map<String, Object> c1 = jdbc.queryForMap(
                "SELECT owner_id, member_count, status FROM circle WHERE id = ?", circleTransfer);
        assertEquals(circleAdmin, ((Number) c1.get("owner_id")).longValue());
        assertEquals(2, ((Number) c1.get("member_count")).intValue());
        assertEquals(0, ((Number) c1.get("status")).intValue());
        assertEquals(1, circleRole(circleTransfer, circleAdmin));
        assertEquals(0, count("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                circleTransfer, cancelUser));

        // 圈管理被圈内封禁时不接圈，改交给普通成员
        Map<String, Object> c1b = jdbc.queryForMap(
                "SELECT owner_id, member_count FROM circle WHERE id = ?", circleTransferBanned);
        assertEquals(memberD, ((Number) c1b.get("owner_id")).longValue());
        assertEquals(2, ((Number) c1b.get("member_count")).intValue());
        assertEquals(1, circleRole(circleTransferBanned, memberD));

        // 圈主注销：只剩余冻结成员 → 解散、清成员、下架圈内帖子
        Map<String, Object> c2 = jdbc.queryForMap(
                "SELECT member_count, status FROM circle WHERE id = ?", circleDisband);
        assertEquals(1, ((Number) c2.get("status")).intValue());
        assertEquals(0, ((Number) c2.get("member_count")).intValue());
        assertEquals(0, count("SELECT COUNT(*) FROM circle_member WHERE circle_id = ?", circleDisband));
        assertEquals(1, count("SELECT COUNT(*) FROM post WHERE circle_id = ? AND status = 2", circleDisband));
        assertEquals(0, joinedCircleCount(frozenMember), "解散后正常成员的加圈数应回退");

        // 普通成员注销：只退出圈子
        Map<String, Object> c3 = jdbc.queryForMap(
                "SELECT owner_id, member_count FROM circle WHERE id = ?", circleMemberOnly);
        assertEquals(otherOwner, ((Number) c3.get("owner_id")).longValue());
        assertEquals(1, ((Number) c3.get("member_count")).intValue());
        assertEquals(0, count("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                circleMemberOnly, cancelUser));

        // 活动报名：删除并回补名额，满员状态回到报名中
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT current_count, status FROM activity WHERE id = ?", activityId);
        assertEquals(0, ((Number) a.get("current_count")).intValue());
        assertEquals(0, ((Number) a.get("status")).intValue());
        assertEquals(0, count("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ? AND user_id = ?",
                activityId, cancelUser));

        // 内容留痕：不挂圈的帖子保留；用户自己的圈子计数归零
        assertEquals(1, count("SELECT COUNT(*) FROM post WHERE author_id = ? AND content = ? "
                + "AND status = 1 AND is_deleted = 0", cancelUser, "注销后保留的帖子"));
        assertEquals(0, joinedCircleCount(cancelUser));

        String detail = jdbc.queryForObject(
                "SELECT detail FROM audit_log WHERE user_id = ? AND action = 'account_cancel' "
                        + "ORDER BY id DESC LIMIT 1", String.class, cancelUser);
        assertNotNull(detail);
        assertTrue(detail.contains("级联清理"));
        assertTrue(detail.contains("退报活动 1"));
    }

    private long user(String tag, int status, int joinedCircleCount, String email) {
        String username = "cc-" + tag + "-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, email, password_hash, nickname, status, role, "
                        + "school_id, verify_status, stranger_msg_enabled, is_deleted, "
                        + "joined_circle_count, create_time) "
                        + "VALUES (?,?,?,?,?,0,1,0,1,0,?,DATE_SUB(NOW(), INTERVAL 4 DAY))",
                username, email, "x", "级联-" + tag, status, joinedCircleCount);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }

    private long group(String tag, long ownerId, int memberCount) {
        String name = "级联-" + tag + "-" + SUFFIX;
        jdbc.update("INSERT INTO `group` (name, owner_id, member_count, status) VALUES (?,?,?,1)",
                name, ownerId, memberCount);
        Long id = jdbc.queryForObject("SELECT id FROM `group` WHERE name = ?", Long.class, name);
        groupIds.add(id);
        return id;
    }

    private void groupMember(long groupId, long userId, int role) {
        jdbc.update("INSERT INTO group_member (group_id, user_id, role) VALUES (?,?,?)",
                groupId, userId, role);
    }

    private long circle(String tag, long ownerId, int memberCount) {
        String name = "级联-" + tag + "-" + SUFFIX;
        jdbc.update("INSERT INTO circle (name, owner_id, member_count, status) VALUES (?,?,?,0)",
                name, ownerId, memberCount);
        Long id = jdbc.queryForObject("SELECT id FROM circle WHERE name = ?", Long.class, name);
        circleIds.add(id);
        return id;
    }

    private void circleMember(long circleId, long userId, int role) {
        jdbc.update("INSERT INTO circle_member (circle_id, user_id, role) VALUES (?,?,?)",
                circleId, userId, role);
    }

    private long activity(String titleSuffix, int capacity, int currentCount, int status) {
        String title = "级联-" + titleSuffix + "-" + SUFFIX;
        jdbc.update("INSERT INTO activity (title, capacity, current_count, start_time, end_time, status) "
                        + "VALUES (?,?,?, DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), ?)",
                title, capacity, currentCount, status);
        Long id = jdbc.queryForObject("SELECT id FROM activity WHERE title = ?", Long.class, title);
        activityIds.add(id);
        return id;
    }

    private void post(long authorId, Long circleId, String content, int status) {
        jdbc.update("INSERT INTO post (author_id, circle_id, content, status) VALUES (?,?,?,?)",
                authorId, circleId, content, status);
        Long id = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? AND content = ?",
                Long.class, authorId, content);
        postIds.add(id);
    }

    private int groupRole(long groupId, long userId) {
        return jdbc.queryForObject("SELECT role FROM group_member WHERE group_id = ? AND user_id = ?",
                Integer.class, groupId, userId);
    }

    private int circleRole(long circleId, long userId) {
        return jdbc.queryForObject("SELECT role FROM circle_member WHERE circle_id = ? AND user_id = ?",
                Integer.class, circleId, userId);
    }

    private int joinedCircleCount(long userId) {
        return jdbc.queryForObject("SELECT joined_circle_count FROM user WHERE id = ?",
                Integer.class, userId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
