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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p>院系/年级/专业字典由本类自建自清理：测试库是新库，仓库里也没有
 * 学校字典种子文件，不能假定字典已由人工导入（原先依赖开发库数据）。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ReverifyOnChangeEventTest {

    /** 名称按运行唯一：上次异常退出残留的行不阻塞本次运行；长度须能放进 user.grade(16) */
    private static final String SUFFIX = java.util.UUID.randomUUID().toString().substring(0, 8);

    /** 学院 A（专业 081001 类） */
    private long deptA;
    private long majorA;
    private String codeA;
    /** 学院 B（专业 080207 类），用于构造「专业与学院对不上」 */
    private long deptB;
    private long majorB;
    private String codeB;

    private long gradeId;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private CampusVerifyService campusVerifyService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uid;

    @BeforeEach
    void setUp() {
        // 两个专业码落在互不重叠的六位区间，保证 A/B 必然不同
        codeA = String.valueOf(100000 + Math.floorMod(System.nanoTime(), 400000));
        codeB = String.valueOf(500000 + Math.floorMod(System.nanoTime(), 400000));

        deptA = insert("INSERT INTO school_dept (name, school_id, sort, enabled, is_deleted) "
                + "VALUES (?, 1, 900, 1, 0)", "测试学院A-" + SUFFIX);
        deptB = insert("INSERT INTO school_dept (name, school_id, sort, enabled, is_deleted) "
                + "VALUES (?, 1, 901, 1, 0)", "测试学院B-" + SUFFIX);
        gradeId = insert("INSERT INTO school_grade (name, enroll_year, sort, enabled, is_deleted) "
                + "VALUES (?, 2023, 900, 1, 0)", "G-" + SUFFIX);
        majorA = insert("INSERT INTO school_major "
                        + "(dept_id, school_id, name, code, level, code_type, sort, enabled, is_deleted) "
                        + "VALUES (?, 1, ?, ?, 1, 1, 900, 1, 0)",
                deptA, "测试专业A-" + SUFFIX, codeA);
        majorB = insert("INSERT INTO school_major "
                        + "(dept_id, school_id, name, code, level, code_type, sort, enabled, is_deleted) "
                        + "VALUES (?, 1, ?, ?, 1, 1, 901, 1, 0)",
                deptB, "测试专业B-" + SUFFIX, codeB);
    }

    @AfterEach
    void cleanup() {
        // 物理删：deleteById 走 @TableLogic，student_no 仍占 uk_student_no，第二轮必撞唯一键
        if (uid != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
        // school_major 对 school_dept 有外键，必须先删专业
        jdbc.update("DELETE FROM school_major WHERE id IN (?, ?)", majorA, majorB);
        jdbc.update("DELETE FROM school_grade WHERE id = ?", gradeId);
        jdbc.update("DELETE FROM school_dept WHERE id IN (?, ?)", deptA, deptB);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
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
        u.setDeptId(deptA);
        u.setGradeId(gradeId);
        u.setMajorId(majorA);
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
        authService.updateProfile(uid, null, null, studentNo(codeA, 1), null);
        assertEquals(1, userMapper.selectById(uid).getVerifyStatus(),
                "格式与归属一致应直接登记");
    }

    /** 格式合法但专业码对不上 → 转核对队列，不拒绝 */
    @Test
    void inconsistentStudentNo_goesToReview() {
        seed(0, null);
        String no = studentNo(codeB, 2);
        authService.updateProfile(uid, null, null, no, null);

        User after = userMapper.selectById(uid);
        assertEquals(2, after.getVerifyStatus(), "对不上应转管理员核对，而不是拒绝");
        assertEquals(no, after.getStudentNo(), "提交的学号应落库");
    }

    /** 已登记改归属 → 重置为未填写 */
    @Test
    void verifiedUserChangesIdentity_resets() {
        seed(1, studentNo(codeA, 3));
        campusVerifyService.saveSchoolProfile(uid, profileOf(deptB, majorB));
        assertEquals(0, userMapper.selectById(uid).getVerifyStatus(),
                "改归属应重置校园信息状态");
    }

    /** 已登记原样保存归属 → 状态保持 */
    @Test
    void verifiedUserSavesSameIdentity_keepsVerified() {
        seed(1, studentNo(codeA, 4));
        campusVerifyService.saveSchoolProfile(uid, profileOf(deptA, majorA));
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
        seed(3, studentNo(codeB, 5));
        authService.updateProfile(uid, null, null, studentNo(codeB, 5), null);
        assertEquals(2, userMapper.selectById(uid).getVerifyStatus(),
                "已驳回同号重提应进核对队列");
    }

    /** 驳回释放学号：状态 3 且 student_no 为 NULL，提交一致学号 → 直接登记 */
    @Test
    void rejectedUserWithReleasedStudentNo_canSubmitNewOne() {
        seed(3, null);
        assertNull(userMapper.selectById(uid).getStudentNo());

        String no = studentNo(codeA, 6);
        authService.updateProfile(uid, null, null, no, null);

        User after = userMapper.selectById(uid);
        assertEquals(1, after.getVerifyStatus(), "一致学号应直接登记");
        assertEquals(no, after.getStudentNo());
    }
}
