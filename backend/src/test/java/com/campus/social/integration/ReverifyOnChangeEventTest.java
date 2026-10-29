package com.campus.social.integration;

import com.campus.social.config.CampusProperties;
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
 * 「改即重审」状态机回归（2026-10-24 建，2026-10-28 改成合法学号）。
 *
 * <p>认证背书绑定「学号 + 归属」这套凭证，凭证变更后背书不再成立：
 * <ul>
 *   <li>已认证(1)/审核中(2) 换学号 → 退回核验队列(2)</li>
 *   <li>已认证(1) 改归属 → 重置未认证(0)</li>
 *   <li>已认证(1) 原样保存归属 → 保持已认证(1)（不误伤）</li>
 *   <li><b>已驳回(3) 同号重新提交 → 进待审队列(2)</b>（2026-10-28 新增）</li>
 * </ul>
 *
 * <p><b>为什么这版换了学号</b>：{@code StudentNoProfileValidator} 上线后，
 * {@code PUT /user/me} 改学号也要过六层校验。旧用例的 {@code 23100000000001}
 * 层次标记位是 {@code 00}、专业码位是 {@code 100000}，第一轮就会被 1058 拒掉——
 * 测试会挂在「校验不通过」上，而不是它想验的状态迁移。现在的号按真实编码拼：
 * {@code 23 | 081001 | 00 | 05 | 01}。
 *
 * <p><b>为什么字典从库里查而不是自己 INSERT</b>：{@code school_grade} 有
 * {@code uk_name} 唯一键，{@code INSERT IGNORE (id=9002,'2023级')} 在本地
 * （真实数据里已有「2023级」）会被静默跳过，id 9002 根本不存在，
 * 测试随即挂在「所选年级不存在」。查现有行既幂等，也不往字典里塞测试脏数据。
 *
 * <p><b>为什么显式关掉自动通过</b>：{@code canAutoPass} 按「学号专业大类 → 学院名」
 * 交叉判定，用真实学院时结果取决于字典内容。本类验的是**状态迁移**，
 * 必须在「一定转人工」的前提下跑，所以把开关按原值记下、跑完还原。
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
    private CampusProperties campus;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uid;
    private Long gradeId;
    private boolean autoApproveBackup;

    @BeforeEach
    void setUp() {
        autoApproveBackup = campus.isAutoApproveStudentNo();
        campus.setAutoApproveStudentNo(false);

        // 2023 级：六层的长度规则要求 14 位学号必须是 23 级
        Map<String, Object> g = jdbc.queryForMap(
                "SELECT id, enroll_year FROM school_grade WHERE enabled = 1 AND is_deleted = 0 "
                        + "AND enroll_year = 2023 ORDER BY id LIMIT 1");
        gradeId = ((Number) g.get("id")).longValue();
        assertNotNull(gradeId);
    }

    @AfterEach
    void cleanup() {
        campus.setAutoApproveStudentNo(autoApproveBackup);
        // 物理删：deleteById 走 @TableLogic，student_no 仍占 uk_student_no，第二轮必撞唯一键
        if (uid != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
    }

    /** 按六层规则拼学号：年级 + 专业码 + 班号 + 层次标记 + 班内序号 */
    private String studentNo(String majorCode, int seq) {
        return "23" + majorCode + "00" + "05" + String.format("%02d", seq);
    }

    private void seed(int verifyStatus, String studentNo) {
        User u = new User();
        u.setUsername("reverify-" + System.nanoTime() + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("重审测试");
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

    /** 已认证换学号 → 退回待审 */
    @Test
    void verifiedUserChangesStudentNo_requeues() {
        seed(1, studentNo(CODE_A, 1));
        authService.updateProfile(uid, null, null, studentNo(CODE_A, 2), null);
        assertEquals(2, userMapper.selectById(uid).getVerifyStatus(),
                "已认证换学号应退回核验队列（改即重审）");
    }

    /** 已认证改归属 → 重置未认证 */
    @Test
    void verifiedUserChangesIdentity_resets() {
        seed(1, studentNo(CODE_A, 3));
        campusVerifyService.saveSchoolProfile(uid, profileOf(DEPT_B, MAJOR_B));
        assertEquals(0, userMapper.selectById(uid).getVerifyStatus(),
                "已认证改归属应重置为未认证");
    }

    /** 已认证原样保存归属 → 认证不丢 */
    @Test
    void verifiedUserSavesSameIdentity_keepsVerified() {
        seed(1, studentNo(CODE_A, 4));
        campusVerifyService.saveSchoolProfile(uid, profileOf(DEPT_A, MAJOR_A));
        assertEquals(1, userMapper.selectById(uid).getVerifyStatus(),
                "原样保存不应弄丢认证");
    }

    /**
     * 已驳回 + 同一个学号重新提交 → 进待审队列。
     *
     * <p>「驳回死循环」的回归点：驳回时 student_no 已释放为 NULL，
     * 正常路径一定判得出「变了」；但老数据里被驳回的账号仍留着旧学号，
     * 只用 {@code !studentNo.equals(old)} 判变化会让这类用户永远提不上去。
     */
    @Test
    void rejectedUserResubmitsSameStudentNo_requeues() {
        String no = studentNo(CODE_A, 5);
        seed(3, no);
        authService.updateProfile(uid, null, null, no, null);
        assertEquals(2, userMapper.selectById(uid).getVerifyStatus(),
                "已驳回同号重提应进待审队列");
    }

    /** 驳回释放学号：状态 3 且 student_no 为 NULL 的账号，换新号同样能进队列 */
    @Test
    void rejectedUserWithReleasedStudentNo_canSubmitNewOne() {
        seed(3, null);
        assertNull(userMapper.selectById(uid).getStudentNo());

        String no = studentNo(CODE_A, 6);
        authService.updateProfile(uid, null, null, no, null);

        User after = userMapper.selectById(uid);
        assertEquals(2, after.getVerifyStatus(), "驳回后换号提交应进待审队列");
        assertEquals(no, after.getStudentNo());
    }

    /** 六层校验对设置页同样生效：专业码对不上的学号不能在编辑资料页蒙混过关 */
    @Test
    void profileEditRejectsStudentNoFailingSixLayerCheck() {
        seed(0, null);
        String wrongMajor = studentNo(CODE_B, 7); // 专业码属于学院 B 的专业，与本人归属不符

        assertThrows(BusinessException.class,
                () -> authService.updateProfile(uid, null, null, wrongMajor, null),
                "六层校验不过应抛业务异常，而不是把脏学号塞进人工队列");

        User after = userMapper.selectById(uid);
        assertEquals(0, after.getVerifyStatus(), "校验不过不该进队列");
        assertNull(after.getStudentNo(), "校验不过不该把学号写进库");
    }
}
