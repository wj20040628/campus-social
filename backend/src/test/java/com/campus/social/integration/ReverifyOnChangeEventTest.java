package com.campus.social.integration;

import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.service.CampusVerifyService;
import com.campus.social.dto.SchoolProfileDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「改即重审」状态机回归(2026-10-24)。
 *
 * 认证背书绑定「学号+归属」这套凭证,凭证变更后背书不再成立:
 *   已认证(1)/审核中(2) 换学号      → 退回核验队列(2)
 *   已认证(1)          改归属       → 重置未认证(0)
 *   已认证(1)          原样保存归属 → 保持已认证(1)(不误伤)
 * 三条路断一条,认证体系就会出现「换了学号认证还在」或「保存一下认证没了」。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReverifyOnChangeEventTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private CampusVerifyService campusVerifyService;

    private Long uid;

    private void seed(int verifyStatus, String studentNo) {
        User u = new User();
        u.setUsername("reverify-" + System.nanoTime() + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("重审测试");
        u.setStudentNo(studentNo);
        u.setVerifyStatus(verifyStatus);
        u.setStatus(0);
        u.setDeptId(1L);
        u.setDept("智慧交通与智能建造工程学院");
        u.setGradeId(9L);
        u.setGrade("2023级");
        u.setMajorId(1L);
        userMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        /*
         * 必须物理删:deleteById 走 @TableLogic 逻辑删除,
         * student_no 仍占 uk_student_no 唯一键,第二轮跑测试必撞库。
         */
        if (uid != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void verifiedUserChangesStudentNo_requeues() {
        seed(1, "23100000000001");
        authService.updateProfile(uid, null, null, "23100000000002", null);
        Integer vs = userMapper.selectById(uid).getVerifyStatus();
        assertEquals(2, vs, "已认证换学号应退回核验队列(改即重审)");
        System.out.println("REVERIFY_OK verified->change-studentNo => " + vs);
    }

    @Test
    void verifiedUserChangesIdentity_resets() {
        seed(1, "23100000000003");
        SchoolProfileDTO dto = new SchoolProfileDTO();
        dto.setSchoolId(1L);
        dto.setDeptId(3L);   // 换学院 → 归属变化
        dto.setGradeId(9L);
        dto.setMajorId(37L); // dept 3 的真实专业(机械工程)
        campusVerifyService.saveSchoolProfile(uid, dto);
        Integer vs = userMapper.selectById(uid).getVerifyStatus();
        assertEquals(0, vs, "已认证改归属应重置为未认证");
        System.out.println("REVERIFY_OK verified->change-identity => " + vs);
    }

    @Test
    void verifiedUserSavesSameIdentity_keepsVerified() {
        seed(1, "23100000000004");
        SchoolProfileDTO dto = new SchoolProfileDTO();
        dto.setSchoolId(1L);
        dto.setDeptId(1L);  // 与 seed 相同:原样保存
        dto.setGradeId(9L);
        dto.setMajorId(1L);
        campusVerifyService.saveSchoolProfile(uid, dto);
        Integer vs = userMapper.selectById(uid).getVerifyStatus();
        assertEquals(1, vs, "原样保存不应弄丢认证");
        System.out.println("REVERIFY_OK verified->same-identity => " + vs);
    }
}
