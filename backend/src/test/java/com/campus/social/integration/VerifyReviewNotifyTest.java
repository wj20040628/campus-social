package com.campus.social.integration;

import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AdminVerifyService;
import com.campus.social.vo.AdminVerifyPageVO;
import com.campus.social.vo.AdminVerifyPendingItemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学号核验的两个 2026-10-28 改动：
 *
 * 1. **结果要通知用户**。此前管理员点了驳回，用户端只有「状态变 3」，
 *    理由只躺在 audit_log 里，用户永远不知道要改什么——驳回等于没发生。
 * 2. **学院一致性要能判**。学号里没有学院，学院由「专业 → 所属学院」推出，
 *    所以比的是 user.dept_id 与 school_major.dept_id。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class VerifyReviewNotifyTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AdminVerifyService adminVerifyService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uid;
    private Long adminId;

    @BeforeEach
    void seed() {
        // 字典最小集：dept 1 有 major 1；major 37 属于 dept 3，用来造「学院与专业不符」
        jdbc.update("INSERT IGNORE INTO school_dept (id, name, school_id, sort, enabled) VALUES "
                + "(1,'测试学院A',1,100,1),(3,'测试学院B',1,100,1)");
        jdbc.update("INSERT IGNORE INTO school_grade (id, name, enroll_year, sort, enabled) VALUES "
                + "(9,'2023级',2023,100,1)");
        jdbc.update("INSERT IGNORE INTO school_major (id, name, dept_id, code, level, enabled) VALUES "
                + "(1,'测试专业甲',1,'080901',1,1),(37,'测试专业乙',3,'080902',1,1)");

        User admin = new User();
        admin.setUsername("vn-admin-" + System.nanoTime() + "@campus.test");
        admin.setPasswordHash("x");
        admin.setNickname("核验管理员");
        admin.setRole(1);
        admin.setStatus(0);
        admin.setVerifyStatus(1);
        userMapper.insert(admin);
        adminId = admin.getId();
    }

    private void seedPending(long deptId, long majorId) {
        User u = new User();
        u.setUsername("vn-" + System.nanoTime() + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("核验测试");
        // 14 位：2 位年份 + 8 位专业/班级 + 4 位流水，nanoTime 取绝对值防负数
        u.setStudentNo(String.format("23%08d0201", Math.abs(System.nanoTime() % 100_000_000L)));
        u.setVerifyStatus(2);
        u.setStatus(0);
        u.setDeptId(deptId);
        u.setDept(deptId == 1L ? "测试学院A" : "测试学院B");
        u.setGradeId(9L);
        u.setGrade("2023级");
        u.setMajorId(majorId);
        userMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        // 物理删：逻辑删除会继续占 uk_student_no，第二轮必撞唯一键
        if (uid != null) {
            jdbc.update("DELETE FROM notify WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
            uid = null;
        }
        if (adminId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", adminId);
            adminId = null;
        }
    }

    @Test
    void reject_notifiesUserWithReason() {
        seedPending(1L, 1L);
        String reason = "学院与学号不匹配，请核对后重新提交";
        adminVerifyService.reject(adminId, uid, reason);

        Integer same = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify WHERE user_id = ? AND type = 17 AND content = ?",
                Integer.class, uid, reason);
        assertEquals(1, same, "驳回必须给用户发一条带理由的通知");

        Integer unread = jdbc.queryForObject(
                "SELECT unread_notify_count FROM user WHERE id = ?", Integer.class, uid);
        assertTrue(unread != null && unread >= 1, "通知要同时把未读数 +1，否则用户看不到红点");
    }

    @Test
    void approve_notifiesUser() {
        seedPending(1L, 1L);
        adminVerifyService.approve(adminId, uid);

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify WHERE user_id = ? AND type = 16",
                Integer.class, uid);
        assertEquals(1, cnt, "通过也要通知：否则用户只能自己去编辑资料页猜结果");
    }

    @Test
    void pending_flagsDeptMismatch() {
        // 学院选 1、专业选 37（属于学院 3）→ 学院与专业不一致
        seedPending(1L, 37L);

        AdminVerifyPageVO page = adminVerifyService.pending(null, 20);
        Optional<AdminVerifyPendingItemVO> mine = page.getList().stream()
                .filter(x -> uid.equals(x.getUserId()))
                .findFirst();
        assertTrue(mine.isPresent(), "刚提交的待审记录应在第一页（按 update_time 倒序）");
        assertNotNull(mine.get().getDeptMatch(), "两侧 id 都在时必须给出判定，不能是 null");
        assertEquals(false, mine.get().getDeptMatch(), "学院与专业所属学院不一致要能判出来");
    }
}
