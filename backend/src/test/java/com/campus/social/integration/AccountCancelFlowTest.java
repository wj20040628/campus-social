package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.LoginDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AccountCancelService;
import com.campus.social.service.AdminUserService;
import com.campus.social.service.LoginService;
import com.campus.social.service.RelationService;
import com.campus.social.util.JwtUtil;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助注销账号特征测试（《个人信息保护法》删除权）。
 *
 * <p>覆盖：验证码确认、字段去标识化、refresh 与状态缓存失效、重复注销拒绝、
 * 管理员拒绝、注销后邮箱释放可重新注册、旧凭证无法登录。
 * 验证码不经过邮件网关，直接写 Redis {@code code:cancel:<email>}。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AccountCancelFlowTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final String EMAIL = "cancel-" + SUFFIX + "@campus.test";
    private static final String PASSWORD = "Cancel123!";
    private static final String CODE = "135790";

    @Autowired
    private AccountCancelService cancelService;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private LoginService loginService;
    @Autowired
    private RelationService relationService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private final List<Long> created = new ArrayList<>();
    private Long uid;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setUsername(EMAIL);
        u.setEmail(EMAIL);
        u.setPasswordHash(JwtUtil.hashPassword(PASSWORD));
        u.setNickname("待注销用户");
        u.setBio("一段个人简介");
        u.setAvatarUrl("/uploads/9/a.jpg");
        u.setCoverUrl("/uploads/9/cover.jpg");
        u.setStudentNo("2308" + SUFFIX + "0001");
        u.setDept("计算机学院");
        u.setDeptId(1L);
        u.setGrade("23级");
        u.setGradeId(1L);
        u.setMajorId(1L);
        u.setCampus(1);
        u.setVerifyStatus(1);
        u.setStatus(0);
        userMapper.insert(u);
        uid = u.getId();
        created.add(uid);
        redis.opsForValue().set("code:cancel:" + EMAIL, CODE);
    }

    @AfterEach
    void cleanup() {
        redis.delete("code:cancel:" + EMAIL);
        redis.delete("code:fail:cancel:" + EMAIL);
        for (Long id : created) {
            redis.delete("refresh:" + id);
            redis.delete("user:status:" + id);
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        created.clear();
    }

    @Test
    void cancelClearsPersonalInformation() {
        cancelService.cancel(uid, CODE);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT username, nickname, email, student_no, bio, avatar_url, cover_url, "
                        + "dept, dept_id, grade_id, major_id, campus, verify_status, "
                        + "password_hash, status, mute_until FROM user WHERE id = ?", uid);
        assertEquals(2, ((Number) row.get("status")).intValue());
        assertEquals("deleted_" + uid, row.get("username"));
        assertEquals("已注销用户", row.get("nickname"));
        assertNull(row.get("email"));
        assertNull(row.get("student_no"));
        assertNull(row.get("bio"));
        assertNull(row.get("avatar_url"));
        assertNull(row.get("cover_url"));
        assertNull(row.get("dept"));
        assertNull(row.get("dept_id"));
        assertNull(row.get("grade_id"));
        assertNull(row.get("major_id"));
        assertNull(row.get("campus"));
        assertEquals(0, ((Number) row.get("verify_status")).intValue());
        assertEquals(JwtUtil.PASSWORD_UNSET, row.get("password_hash"));
        assertNull(row.get("mute_until"));
    }

    @Test
    void cancelRevokesRefreshAndEvictsStatusCache() {
        redis.opsForValue().set("refresh:" + uid, "jti-before-cancel");
        redis.opsForValue().set("user:status:" + uid, "0");

        cancelService.cancel(uid, CODE);

        assertNull(redis.opsForValue().get("refresh:" + uid), "refresh 令牌必须吊销");
        assertNull(redis.opsForValue().get("user:status:" + uid), "状态缓存必须失效");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'account_cancel'",
                Integer.class, uid));
    }

    @Test
    void wrongCodeKeepsAccountIntact() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> cancelService.cancel(uid, "000000"));
        assertEquals(ResultCode.CODE_INVALID.getCode(), ex.getCode());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, email, nickname FROM user WHERE id = ?", uid);
        assertEquals(0, ((Number) row.get("status")).intValue());
        assertEquals(EMAIL, row.get("email"));
        assertEquals("待注销用户", row.get("nickname"));
    }

    @Test
    void secondCancelIsRejected() {
        cancelService.cancel(uid, CODE);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> cancelService.cancel(uid, CODE));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void adminCannotCancelSelf() {
        User admin = new User();
        admin.setUsername("cancel-admin-" + SUFFIX + "@campus.test");
        admin.setEmail("cancel-admin-" + SUFFIX + "@campus.test");
        admin.setPasswordHash(JwtUtil.hashPassword(PASSWORD));
        admin.setNickname("管理员");
        admin.setRole(1);
        admin.setStatus(0);
        userMapper.insert(admin);
        created.add(admin.getId());
        redis.opsForValue().set("code:cancel:" + admin.getEmail(), CODE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cancelService.cancel(admin.getId(), CODE));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), ex.getCode());
        assertEquals(0, userMapper.selectById(admin.getId()).getStatus().intValue());
    }

    @Test
    void emailIsReleasedAndOldPasswordNoLongerWorks() {
        cancelService.cancel(uid, CODE);

        assertNull(userMapper.selectByEmail(EMAIL), "注销后邮箱必须释放");

        // 旧凭证：原邮箱已不是登录标识，密码登录只能得到「账号或密码错误」
        LoginDTO dto = new LoginDTO();
        dto.setUsername(EMAIL);
        dto.setPassword(PASSWORD);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> loginService.login(dto, "127.0.0.1"));
        assertEquals(ResultCode.WRONG_PASSWORD.getCode(), ex.getCode());
    }

    @Test
    void adminCannotBanCancelledAccount() {
        cancelService.cancel(uid, CODE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.ban(1L, uid, 7, "测试"));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), ex.getCode());
        assertEquals(2, userMapper.selectById(uid).getStatus().intValue(),
                "封禁被拒后必须保持已注销状态");
        // 若误写入解封计划，到期任务会把 status 改回 0，账号就被复活了
        assertNull(redis.opsForZSet().score("ban:expiring", String.valueOf(uid)));
    }

    @Test
    void cancelledUserIsHiddenFromFollowListsAndCannotBeFollowed() {
        User viewer = new User();
        viewer.setUsername("cancel-viewer-" + SUFFIX + "@campus.test");
        viewer.setPasswordHash("x");
        viewer.setNickname("观察者");
        viewer.setStatus(0);
        userMapper.insert(viewer);
        created.add(viewer.getId());

        cancelService.cancel(uid, CODE);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type) VALUES (?,?,1)",
                viewer.getId(), uid);
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type) VALUES (?,?,1)",
                uid, viewer.getId());

        assertTrue(relationService.following(viewer.getId(), null, 20).getList().isEmpty(),
                "关注列表不得出现已注销账号");
        assertTrue(relationService.followers(viewer.getId(), null, 20).getList().isEmpty(),
                "粉丝列表不得出现已注销账号（@ 推荐人取自这两份列表）");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> relationService.follow(viewer.getId(), uid));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void sameEmailCanRegisterAgainAfterCancel() {
        cancelService.cancel(uid, CODE);

        User fresh = new User();
        fresh.setUsername(EMAIL);
        fresh.setEmail(EMAIL);
        fresh.setPasswordHash(JwtUtil.hashPassword(PASSWORD));
        fresh.setNickname("重新注册");
        fresh.setStatus(0);
        userMapper.insert(fresh);
        created.add(fresh.getId());

        assertTrue(fresh.getId() > uid);
        assertEquals(EMAIL, userMapper.selectByEmail(EMAIL).getEmail());
    }
}
