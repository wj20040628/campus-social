package com.campus.social.integration;

import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.task.ReconcileJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对账任务覆盖（2026-11-01，1.13.11）：
 * 群成员数、活动报名数、用户加圈数、私信未读数四项此前没有对账，
 * 一旦某条写路径漏减，计数会永久漂移。这里故意写坏计数，跑一次全量对账验证修正。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ReconcileIntegrationTest {

    @Autowired private ReconcileJob reconcileJob;
    @Autowired private GroupMapper groupMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ChatConversationMapper chatConversationMapper;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> circleIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> convIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long conv : convIds) {
            jdbc.update("DELETE FROM chat_message WHERE conv_id = ?", conv);
            jdbc.update("DELETE FROM chat_conversation WHERE id = ?", conv);
        }
        for (Long activity : activityIds) {
            jdbc.update("DELETE FROM activity_signup WHERE activity_id = ?", activity);
            jdbc.update("DELETE FROM activity WHERE id = ?", activity);
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
        for (Long user : userIds) {
            jdbc.update("DELETE FROM notify WHERE user_id = ?", user);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", user);
            jdbc.update("DELETE FROM user_tag WHERE user_id = ?", user);
            jdbc.update("DELETE FROM user WHERE id = ?", user);
        }
        userIds.clear();
        groupIds.clear();
        circleIds.clear();
        activityIds.clear();
        convIds.clear();
    }

    @Test
    void reconcileFixesGroupActivityCircleAndChatCounters() {
        long a = user("rec-a");
        long b = user("rec-b");
        long group = group(a, 99);
        jdbc.update("INSERT INTO group_member (group_id, user_id, role) VALUES (?,?,1)", group, a);
        jdbc.update("INSERT INTO group_member (group_id, user_id, role) VALUES (?,?,2)", group, b);
        long circle = circle(a, 2);
        jdbc.update("INSERT INTO circle_member (circle_id, user_id, role) VALUES (?,?,1)", circle, a);
        jdbc.update("INSERT INTO circle_member (circle_id, user_id, role) VALUES (?,?,3)", circle, b);
        jdbc.update("UPDATE user SET joined_circle_count = 99 WHERE id IN (?,?)", a, b);
        long activity = activity(a, 10, 99, 0);
        jdbc.update("INSERT INTO activity_signup (activity_id, user_id) VALUES (?,?)", activity, a);
        jdbc.update("INSERT INTO activity_signup (activity_id, user_id) VALUES (?,?)", activity, b);
        long conv = conversation(a, b, 99, 99);
        message(conv, a, b); // b 未读
        message(conv, b, a); // a 未读

        reconcileJob.reconcileAll();

        assertEquals(2, queryInt("SELECT member_count FROM `group` WHERE id = ?", group));
        assertEquals(2, queryInt("SELECT current_count FROM activity WHERE id = ?", activity));
        assertEquals(0, queryInt("SELECT status FROM activity WHERE id = ?", activity));
        assertEquals(1, queryInt("SELECT joined_circle_count FROM user WHERE id = ?", a));
        assertEquals(1, queryInt("SELECT joined_circle_count FROM user WHERE id = ?", b));
        assertEquals(1, queryInt("SELECT unread_a FROM chat_conversation WHERE id = ?", conv));
        assertEquals(1, queryInt("SELECT unread_b FROM chat_conversation WHERE id = ?", conv));

        assertEquals(0, groupMapper.countMemberCountDrift());
        assertEquals(0, activityMapper.countCurrentCountDrift());
        assertEquals(0, userMapper.countJoinedCircleDrift());
        assertEquals(0, chatConversationMapper.countUnreadDrift());
    }

    private long user(String tag) {
        String username = tag + "-" + System.nanoTime() + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,0,0,1,0,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                username, "x", tag);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }

    private long group(long owner, int memberCount) {
        String name = "reconcile-group-" + System.nanoTime();
        jdbc.update("INSERT INTO `group` (name, owner_id, member_count, status) VALUES (?,?,?,1)",
                name, owner, memberCount);
        Long id = jdbc.queryForObject("SELECT id FROM `group` WHERE name = ?", Long.class, name);
        groupIds.add(id);
        return id;
    }

    private long circle(long owner, int memberCount) {
        String name = "reconcile-circle-" + System.nanoTime();
        jdbc.update("INSERT INTO circle (name, owner_id, member_count, status) VALUES (?,?,?,0)",
                name, owner, memberCount);
        Long id = jdbc.queryForObject("SELECT id FROM circle WHERE name = ?", Long.class, name);
        circleIds.add(id);
        return id;
    }

    private long activity(long creator, int capacity, int currentCount, int status) {
        String title = "reconcile-activity-" + System.nanoTime();
        jdbc.update("INSERT INTO activity (title, creator_id, capacity, current_count, "
                        + "start_time, end_time, status) "
                        + "VALUES (?,?,?,?, DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), ?)",
                title, creator, capacity, currentCount, status);
        Long id = jdbc.queryForObject("SELECT id FROM activity WHERE title = ?", Long.class, title);
        activityIds.add(id);
        return id;
    }

    private long conversation(long memberA, long memberB, int unreadA, int unreadB) {
        jdbc.update("INSERT INTO chat_conversation (member_a, member_b, unread_a, unread_b) VALUES (?,?,?,?)",
                memberA, memberB, unreadA, unreadB);
        Long id = jdbc.queryForObject(
                "SELECT id FROM chat_conversation WHERE member_a = ? AND member_b = ?",
                Long.class, memberA, memberB);
        convIds.add(id);
        return id;
    }

    private void message(long convId, long sender, long receiver) {
        jdbc.update("INSERT INTO chat_message (conv_id, sender_id, receiver_id, content, status) "
                + "VALUES (?,?,?,?,0)", convId, sender, receiver, "reconcile");
    }

    private int queryInt(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
