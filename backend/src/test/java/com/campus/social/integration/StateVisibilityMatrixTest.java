package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.ActivityService;
import com.campus.social.service.CircleService;
import com.campus.social.service.impl.CircleMemberAdminService;
import com.campus.social.vo.CircleMemberVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 状态 × 角色 × 可见性矩阵（2026-11-01，1.13.11）。
 *
 * <p>此前守卫按接口逐个补，容易出现「详情拦了、报名没拦」「前端隐藏了、接口还返回」。
 * 这组参数化用例固定三类矩阵：圈子可用性、活动状态、成员列表查看者角色。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class StateVisibilityMatrixTest {

    @Autowired private ActivityService activityService;
    @Autowired private CircleService circleService;
    @Autowired private CircleMemberAdminService circleMemberAdminService;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> circleIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();

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
            jdbc.update("DELETE FROM user_tag WHERE user_id = ?", id);
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        activityIds.clear();
        circleIds.clear();
        userIds.clear();
    }

    @ParameterizedTest(name = "圈子状态 {0} → 可加入={1}")
    @CsvSource({"0,true", "1,false"})
    void joinOnlyWhenCircleActive(int circleStatus, boolean allowed) {
        long owner = user("jm-owner", 0, 0);
        long outsider = user("jm-outsider", 0, 0);
        long circle = circle(owner, circleStatus, 1);
        member(circle, owner, 1, 0);

        if (allowed) {
            circleService.join(circle, outsider);
            assertEquals(1, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                    circle, outsider));
        } else {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> circleService.join(circle, outsider));
            assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
            assertEquals(0, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                    circle, outsider));
        }
    }

    @ParameterizedTest(name = "活动所属圈状态 {0} → 可读/可报名={1}")
    @CsvSource({"0,true", "1,false"})
    void activityOnlyVisibleWhenCircleActive(int circleStatus, boolean visible) {
        long owner = user("am-owner", 0, 0);
        long viewer = user("am-viewer", 0, 0);
        long circle = circle(owner, circleStatus, 1);
        member(circle, owner, 1, 0);
        long activity = activity(circle, owner, 10, 0, 0);

        if (visible) {
            assertEquals(activity, activityService.detail(activity, viewer).getId());
            assertEquals(1, activityService.listByCircle(circle, null, 20, viewer).getList().size());
            activityService.signup(activity, viewer);
            assertEquals(1, queryInt("SELECT current_count FROM activity WHERE id = ?", activity));
        } else {
            BusinessException detail = assertThrows(BusinessException.class,
                    () -> activityService.detail(activity, viewer));
            assertEquals(ResultCode.NOT_FOUND.getCode(), detail.getCode());
            BusinessException list = assertThrows(BusinessException.class,
                    () -> activityService.listByCircle(circle, null, 20, viewer));
            assertEquals(ResultCode.NOT_FOUND.getCode(), list.getCode());
            BusinessException signup = assertThrows(BusinessException.class,
                    () -> activityService.signup(activity, viewer));
            assertEquals(ResultCode.NOT_FOUND.getCode(), signup.getCode());
            assertEquals(0, queryInt("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ?", activity));
        }
    }

    @ParameterizedTest(name = "查看者 {0} → 成员列表 {1} 人")
    @CsvSource({"owner,4", "admin,4", "member,3", "outsider,3", "platformAdmin,4"})
    void bannedMemberVisibilityByViewer(String viewerType, int expectedSize) {
        long owner = user("vm-owner", 0, 0);
        long admin = user("vm-admin", 0, 0);
        long member = user("vm-member", 0, 0);
        long banned = user("vm-banned", 0, 0);
        long outsider = user("vm-outsider", 0, 0);
        long platformAdmin = user("vm-platform-admin", 0, 1);
        long circle = circle(owner, 0, 4);
        member(circle, owner, 1, 0);
        member(circle, admin, 2, 0);
        member(circle, member, 3, 0);
        member(circle, banned, 3, 1);

        long viewer = switch (viewerType) {
            case "owner" -> owner;
            case "admin" -> admin;
            case "member" -> member;
            case "platformAdmin" -> platformAdmin;
            default -> outsider;
        };
        List<CircleMemberVO> rows = circleMemberAdminService.members(circle, viewer);
        assertEquals(expectedSize, rows.size(),
                "查看者 " + viewerType + " 的成员列表可见人数不符");
        if (expectedSize == 3) {
            assertEquals(0L, rows.stream().filter(m -> m.getUserId().equals(banned)).count(),
                    "普通访客/普通成员不应看到被封禁成员");
        }
    }

    @ParameterizedTest(name = "圈子状态 {0} 时被封禁成员退圈 → {1}")
    @CsvSource({"0,BLOCKED", "1,ALLOWED"})
    void bannedMemberLeaveDependsOnCircleStatus(int circleStatus, String expected) {
        long owner = user("lm-owner", 0, 0);
        long banned = user("lm-banned", 0, 0);
        long circle = circle(owner, circleStatus, 2);
        member(circle, owner, 1, 0);
        member(circle, banned, 3, 1);

        if ("BLOCKED".equals(expected)) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> circleService.leave(circle, banned));
            assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode());
            assertEquals(1, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                    circle, banned));
        } else {
            circleService.leave(circle, banned);
            assertEquals(0, queryInt("SELECT COUNT(*) FROM circle_member WHERE circle_id = ? AND user_id = ?",
                    circle, banned), "圈子已封禁时应允许退圈，避免残行无法清理");
        }
    }

    @ParameterizedTest(name = "活动状态 {0} 报名 → {1}")
    @CsvSource({"0,OK", "1,FULL", "3,OVER", "4,CANCELLED"})
    void signupByActivityStatus(int activityStatus, String expected) {
        long creator = user("sm-creator", 0, 0);
        long signer = user("sm-signer", 0, 0);
        int capacity = activityStatus == 1 ? 1 : 10;
        int current = activityStatus == 1 ? 1 : 0;
        long activity = activity(null, creator, capacity, current, activityStatus);

        if ("OK".equals(expected)) {
            activityService.signup(activity, signer);
            assertEquals(1, queryInt("SELECT current_count FROM activity WHERE id = ?", activity));
            return;
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> activityService.signup(activity, signer));
        assertEquals(switch (expected) {
            case "FULL" -> ResultCode.ACTIVITY_FULL.getCode();
            case "OVER" -> ResultCode.ACTIVITY_OVER.getCode();
            default -> ResultCode.ACTIVITY_CANCELLED.getCode();
        }, e.getCode());
        assertEquals(0, queryInt("SELECT COUNT(*) FROM activity_signup WHERE activity_id = ?", activity));
    }

    @Test
    void circleMemberListNeverLeaksBannedToAnonymousViewer() {
        long owner = user("anon-owner", 0, 0);
        long banned = user("anon-banned", 0, 0);
        long circle = circle(owner, 0, 2);
        member(circle, owner, 1, 0);
        member(circle, banned, 3, 1);

        List<CircleMemberVO> rows = circleMemberAdminService.members(circle, 0);
        assertEquals(1, rows.size(), "游客只能看到正常成员");
        assertEquals(owner, rows.get(0).getUserId());
    }

    private long user(String tag, int status, int role) {
        String username = tag + "-" + System.nanoTime() + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,?,?,1,0,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                username, "x", tag, status, role);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        userIds.add(id);
        return id;
    }

    private long circle(long owner, int status, int memberCount) {
        String name = "matrix-circle-" + System.nanoTime();
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

    private long activity(Long circleId, long creator, int capacity, int current, int status) {
        String title = "matrix-activity-" + System.nanoTime();
        jdbc.update("INSERT INTO activity (title, circle_id, creator_id, capacity, current_count, "
                        + "start_time, end_time, status) "
                        + "VALUES (?,?,?,?,?, DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), ?)",
                title, circleId, creator, capacity, current, status);
        Long id = jdbc.queryForObject("SELECT id FROM activity WHERE title = ?", Long.class, title);
        activityIds.add(id);
        return id;
    }

    private int queryInt(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
