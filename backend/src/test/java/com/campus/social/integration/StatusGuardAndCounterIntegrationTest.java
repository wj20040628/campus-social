package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.ActivityService;
import com.campus.social.service.CircleAdminService;
import com.campus.social.service.CircleService;
import com.campus.social.service.RelationService;
import com.campus.social.service.impl.CircleMemberAdminService;
import com.campus.social.vo.CircleMemberVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.13.10 回归：并发重复操作的计数幂等、圈子/活动状态守卫、成员列表封禁信息收口。
 *
 * <p>并发用例不追求每次都撞出竞态，而是验证修复后的最终不变量：
 * 同一用户重复取消/退出只能影响一次计数。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class StatusGuardAndCounterIntegrationTest {

    @Autowired private ActivityService activityService;
    @Autowired private CircleService circleService;
    @Autowired private RelationService relationService;
    @Autowired private CircleAdminService circleAdminService;
    @Autowired private CircleMemberAdminService circleMemberAdminService;
    @Autowired private UserMapper userMapper;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> circleIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private long seq = 0;

    @AfterEach
    void cleanup() {
        for (Long id : activityIds) {
            jdbc.update("DELETE FROM activity_signup WHERE activity_id = ?", id);
            jdbc.update("DELETE FROM activity WHERE id = ?", id);
        }
        for (Long id : circleIds) {
            jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", id);
            jdbc.update("DELETE FROM circle WHERE id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM notify WHERE user_id = ?", id);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", id);
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM chat_conv_pref WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        activityIds.clear();
        circleIds.clear();
        userIds.clear();
    }

    @Test
    void concurrentActivityCancelDecrementsOnce() throws Exception {
        long a = user("act-a", 0, 0);
        long b = user("act-b", 0, 0);
        long act = activity(null, null, 10, 2, 0);
        signup(act, a);
        signup(act, b);

        runConcurrently(8, () -> activityService.cancel(act, a));

        assertEquals(1, queryInt("SELECT current_count FROM activity WHERE id = ?", act),
                "并发取消同一人的报名只能减一次名额");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ?", act),
                "另一个报名者应保留");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ? AND user_id = ?",
                act, b));
    }

    @Test
    void concurrentCircleLeaveDecrementsOnce() throws Exception {
        long owner = user("circle-owner", 0, 0);
        long a = user("circle-a", 0, 0);
        long b = user("circle-b", 0, 0);
        long c = circle(owner, 0, 3);
        member(c, owner, 1, 0);
        member(c, a, 3, 0);
        member(c, b, 3, 0);
        jdbc.update("UPDATE user SET joined_circle_count = 1 WHERE id IN (?,?)", a, b);

        runConcurrently(8, () -> circleService.leave(c, a));

        assertEquals(2, queryInt("SELECT member_count FROM circle WHERE id = ?", c),
                "并发重复退圈不能把圈子人数多减");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?", c, a));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?", c, b));
    }

    @Test
    void concurrentUnfollowDecrementsOnce() {
        long a = user("rel-a", 0, 0);
        long b = user("rel-b", 0, 0);
        long c = user("rel-c", 0, 0);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", a, b);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", c, b);
        jdbc.update("UPDATE user SET following_count = 1 WHERE id = ?", a);
        jdbc.update("UPDATE user SET follower_count = 2 WHERE id = ?", b);

        runConcurrentlyUnchecked(8, () -> relationService.unfollow(a, b));

        assertEquals(0, queryInt("SELECT COUNT(*) FROM relation WHERE from_user_id = ? AND to_user_id = ? AND type = 1", a, b));
        assertEquals(0, queryInt("SELECT following_count FROM user WHERE id = ?", a),
                "并发取关只能减一次关注数(1→0,不能重复减)");
        assertEquals(1, queryInt("SELECT follower_count FROM user WHERE id = ?", b),
                "只能减一次粉丝数，另一个粉丝仍应保留");
    }

    @Test
    void joinRejectsBannedOrDisbandedCircle() {
        long owner = user("join-owner", 0, 0);
        long outsider = user("join-outsider", 0, 0);
        long c = circle(owner, 1, 1);
        member(c, owner, 1, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> circleService.join(c, outsider));
        assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?", c, outsider));
        assertEquals(1, queryInt("SELECT member_count FROM circle WHERE id = ?", c));
    }

    @Test
    void bannedMemberCannotLeaveToBypassBan() {
        long owner = user("ban-owner", 0, 0);
        long banned = user("ban-member", 0, 0);
        long c = circle(owner, 0, 2);
        member(c, owner, 1, 0);
        member(c, banned, 3, 1);

        BusinessException e = assertThrows(BusinessException.class, () -> circleService.leave(c, banned));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode());
        assertEquals(1, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?", c, banned),
                "封禁期间退圈再入圈会清掉 ban_status，必须拦住");
        assertEquals(2, queryInt("SELECT member_count FROM circle WHERE id = ?", c));
    }

    @Test
    void activityOfBannedCircleIsHiddenAndCannotSignup() {
        long owner = user("act-circle-owner", 0, 0);
        long u = user("act-signup", 0, 0);
        long c = circle(owner, 1, 1);
        member(c, owner, 1, 0);
        long act = activity(c, owner, 10, 0, 0);

        BusinessException detail = assertThrows(BusinessException.class,
                () -> activityService.detail(act, u));
        assertEquals(ResultCode.NOT_FOUND.getCode(), detail.getCode(), "详情应被圈子状态守卫拦截");
        BusinessException list = assertThrows(BusinessException.class,
                () -> activityService.listByCircle(c, null, 20, u));
        assertEquals(ResultCode.NOT_FOUND.getCode(), list.getCode(), "列表应被圈子状态守卫拦截");
        BusinessException signup = assertThrows(BusinessException.class,
                () -> activityService.signup(act, u));
        assertEquals(ResultCode.NOT_FOUND.getCode(), signup.getCode(), "报名应被圈子状态守卫拦截");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ?", act));
    }

    @Test
    void circleMembersHideBannedFromOutsidersButNotAdmins() {
        long owner = user("list-owner", 0, 0);
        long banned = user("list-banned", 0, 0);
        long normal = user("list-normal", 0, 0);
        long outsider = user("list-outsider", 0, 0);
        long platformAdmin = user("list-admin", 0, 1);
        long c = circle(owner, 0, 3);
        member(c, owner, 1, 0);
        member(c, banned, 3, 1);
        member(c, normal, 3, 0);

        List<CircleMemberVO> publicList = circleMemberAdminService.members(c, outsider);
        assertEquals(2, publicList.size(), "公开成员列表不应暴露被封禁成员");
        assertTrue(publicList.stream().noneMatch(m -> m.getUserId().equals(banned)));

        assertEquals(3, circleMemberAdminService.members(c, owner).size(), "圈主应看到封禁成员");
        assertEquals(3, circleMemberAdminService.members(c, platformAdmin).size(), "平台管理员应看到封禁成员");
    }

    @Test
    void bannedMemberCannotBeAppointedOrMadeOwner() {
        long owner = user("appoint-owner", 0, 0);
        long banned = user("appoint-banned", 0, 0);
        long platformAdmin = user("appoint-admin", 0, 1);
        long c = circle(owner, 0, 2);
        member(c, owner, 1, 0);
        member(c, banned, 3, 1);

        BusinessException appoint = assertThrows(BusinessException.class,
                () -> circleAdminService.appointAdmin(c, banned, owner));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), appoint.getCode());
        assertEquals(3, queryInt("SELECT role FROM circle_member WHERE circle_id = ? AND user_id = ?", c, banned));

        BusinessException transfer = assertThrows(BusinessException.class,
                () -> circleAdminService.transferCircle(c, banned, platformAdmin));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), transfer.getCode());
        assertEquals(owner, jdbc.queryForObject("SELECT owner_id FROM circle WHERE id = ?", Long.class, c));
    }

    @Test
    void reconcileCountsExcludesCancelledUsers() {
        long active = user("rec-active", 0, 0);
        long peer = user("rec-peer", 0, 0);
        long cancelled = user("rec-cancelled", 2, 0);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", active, peer);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", cancelled, peer);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", peer, cancelled);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)", peer, active);
        jdbc.update("UPDATE user SET follower_count = 9, following_count = 9 WHERE id = ?", peer);

        userMapper.reconcileAll();

        assertEquals(1, queryInt("SELECT follower_count FROM user WHERE id = ?", peer),
                "已注销账号的关注不应计入对方粉丝数");
        assertEquals(1, queryInt("SELECT following_count FROM user WHERE id = ?", peer),
                "已注销账号不应计入对方关注数");
        assertEquals(1, queryInt("SELECT follower_count FROM user WHERE id = ?", active));
        assertEquals(1, queryInt("SELECT following_count FROM user WHERE id = ?", active));
    }

    private long user(String tag, int status, int role) {
        String username = "sg-" + tag + "-" + (++seq) + "-" + System.nanoTime() + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,?,?,1,0,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                username, "x", username, status, role);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }

    private long circle(long owner, int status, int memberCount) {
        String name = "sg" + System.nanoTime() + "-" + (++seq);
        jdbc.update("INSERT INTO circle (name, owner_id, member_count, status) VALUES (?,?,?,?)",
                name, owner, memberCount, status);
        Long id = jdbc.queryForObject("SELECT id FROM circle WHERE name = ?", Long.class, name);
        circleIds.add(id);
        return id;
    }

    private void member(long circleId, long userId, int role, int banStatus) {
        jdbc.update("INSERT INTO circle_member (circle_id, user_id, role, ban_status) VALUES (?,?,?,?)",
                circleId, userId, role, banStatus);
    }

    private long activity(Long circleId, Long creatorId, int capacity, int currentCount, int status) {
        String title = "sg-activity-" + (++seq) + "-" + System.nanoTime();
        jdbc.update("INSERT INTO activity (title, circle_id, creator_id, capacity, current_count, "
                        + "start_time, end_time, status) "
                        + "VALUES (?,?,?,?,?, DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), ?)",
                title, circleId, creatorId, capacity, currentCount, status);
        Long id = jdbc.queryForObject("SELECT id FROM activity WHERE title = ?", Long.class, title);
        activityIds.add(id);
        return id;
    }

    private void signup(long activityId, long userId) {
        jdbc.update("INSERT INTO activity_signup (activity_id, user_id) VALUES (?,?)", activityId, userId);
    }

    private int queryInt(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void runConcurrently(int threads, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    action.run();
                } catch (Exception ignored) {
                    // 并发下部分请求会走幂等/等待分支，最终不变量由断言验证
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
    }

    private void runConcurrentlyUnchecked(int threads, Runnable action) {
        try {
            runConcurrently(threads, action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
