package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.service.CampusVerifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 校园信息状态机（2026-10-30 重写）。
 *
 * <p>校园信息已退出权限判定，本类只验证「资料状态与学号一致性」：
 * <ul>
 *   <li>学号格式与所选院系/年级/专业一致 → 直接登记（1）</li>
 *   <li>格式合法但一致性对不上 → 转管理员核对（2），<b>不拒绝用户</b></li>
 *   <li>格式不合法 → 422 1058，不写库</li>
 *   <li>已登记/核对中 改归属 → 状态重置为未填写（0）</li>
 *   <li>改归属但原样保存 → 状态保持（不误伤）</li>
 * </ul>
 *
 * <p>不再存在「六层校验硬拒」与「自动通过开关」：学校不提供学生名单，
 * 任何规则都只能判断资料自洽，不能判断身份真伪。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReverifyOnChangeEventTest {

    /** 学院 A（智慧交通与智能建造工程学院）+ 专业 081001 */
    private static final long DEPT_A = 1L;
    private static final long MAJOR_A = 1L;
    private static final String CODE_A = "081001";
    /** 学院 B（智能车辆与新能源工程学院）+ 专业 080207 */
    private static final long DEPT_B = 2L;
    private static final long MAJOR_B = 15L;
    private static final String CODE_B = "080207";

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private CampusVerifyService campusVerifyService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uid;
    private Long gradeId;

    @BeforeEach
    void setUp() {
        Map<String, Object> g = jdbc.queryForMap(
                "SELECT id, enroll_year FROM school_grade WHERE enabled = 1 AND is_deleted = 0 "
                        + "AND enroll_year = 2023 ORDER BY id LIMIT 1");
        gradeId = ((Number) g.get("id")).longValue();
        assertNotNull(gradeId);
    }

    @AfterEach
    void cleanup() {
        // 物理删：deleteById 走 @TableLogic，student_no 仍占 uk_student_no，第二轮必撞唯一键
        if (uid != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
    }

    /** 年级 + 专业码 + 班号 + 层次标记 + 班内序号 */
    private String studentNo(String majorCode, int seq) {
        return "23" + majorCode + "00" + "05" + String.format("%02d", seq);
    }

    private void seed(int verifyStatus, String studentNo) {
        User u = new User();
        u.setUsername("reverify-" + System.nanoTime() + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("状态测试");
        u.setStudentNo(studentNo);
        u.setVerifyStatus(verifyStatus);
        u.setStatus(0);
        u.setDeptId(DEPT_A);
        u.setGradeId(gradeId);
        u.setMajorId(MAJOR_A);
        userMapper.insert(u);
        uid = u.getId();
    }

    private SchoolProfileDTO profileOf(long deptId, long majorId) {
        SchoolProfileDTO dto = new SchoolProfileDTO();
        dto.setDeptId(deptId);
        dto.setGradeId(gradeId);
        dto.setMajorId(majorId);
        return dto;
    }

    /** 学号与院系/年级/专业一致 → 直接登记为 1 */
    @Test
    void consistentStudentNo_registersImmediately() {
        seed(0, null);
        authService.updateProfile(uid, null, null, studentNo(CODE_A, 1), null);
        assertEquals(1, userMapper.selectById(uid).getVerifyStatus(),
                "格式与归属一致应直接登记");
    }

    /** 格式合法但专业码对不上 → 转核对队列，不拒绝 */
    @Test
    void inconsistentStudentNo_goesToReview() {
        seed(0, null);
        String no = studentNo(CODE_B, 2);
        authService.updateProfile(uid, null, null, no, null);

        User after = userMapper.selectById(uid);
        assertEquals(2, after.getVerifyStatus(), "对不上应转管理员核对，而不是拒绝");
        assertEquals(no, after.getStudentNo(), "提交的学号应落库");
    }

    /** 已登记改归属 → 重置为未填写 */
    @Test
    void verifiedUserChangesIdentity_resets() {
        seed(1, studentNo(CODE_A, 3));
        campusVerifyService.saveSchoolProfile(uid, profileOf(DEPT_B, MAJOR_B));
        assertEquals(0, userMapper.selectById(uid).getVerifyStatus(),
                "改归属应重置校园信息状态");
    }

    /** 已登记原样保存归属 → 状态保持 */
    @Test
    void verifiedUserSavesSameIdentity_keepsVerified() {
        seed(1, studentNo(CODE_A, 4));
        campusVerifyService.saveSchoolProfile(uid, profileOf(DEPT_A, MAJOR_A));
        assertEquals(1, userMapper.selectById(uid).getVerifyStatus(),
                "原样保存不应清掉登记状态");
    }

    /** 格式不合法 → 422 1058，不写库、不改状态 */
    @Test
    void invalidStudentNoFormat_isRejected() {
        seed(0, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.updateProfile(uid, null, null, "123456789012", null));
        assertEquals(ResultCode.STUDENT_NO_MISMATCH.getCode(), ex.getCode());

        User after = userMapper.selectById(uid);
        assertEquals(0, after.getVerifyStatus(), "格式错误不该改状态");
        assertNull(after.getStudentNo(), "格式错误不该把学号写进库");
    }

    /** 已驳回 + 不一致学号重新提交 → 进核对队列 */
    @Test
    void rejectedUserResubmitsInconsistentStudentNo_requeues() {
        seed(3, studentNo(CODE_B, 5));
        authService.updateProfile(uid, null, null, studentNo(CODE_B, 5), null);
        assertEquals(2, userMapper.selectById(uid).getVerifyStatus(),
                "已驳回同号重提应进核对队列");
    }

    /** 驳回释放学号：状态 3 且 student_no 为 NULL，提交一致学号 → 直接登记 */
    @Test
    void rejectedUserWithReleasedStudentNo_canSubmitNewOne() {
        seed(3, null);
        assertNull(userMapper.selectById(uid).getStudentNo());

        String no = studentNo(CODE_A, 6);
        authService.updateProfile(uid, null, null, no, null);

        User after = userMapper.selectById(uid);
        assertEquals(1, after.getVerifyStatus(), "一致学号应直接登记");
        assertEquals(no, after.getStudentNo());
    }
}
