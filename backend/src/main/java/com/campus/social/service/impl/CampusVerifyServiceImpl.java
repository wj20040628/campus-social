package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.dto.CampusEmailConfirmDTO;
import com.campus.social.dto.CampusEmailSendDTO;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.CampusVerifyService;
import com.campus.social.service.DictService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static com.campus.social.common.ResultCode.CAMPUS_EMAIL_NOT_ALLOWED;
import static com.campus.social.common.ResultCode.CONFLICT;
import static com.campus.social.common.ResultCode.SCHOOL_PROFILE_INCOMPLETE;
import static com.campus.social.common.ResultCode.STUDENT_NO_EXISTS;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;
import static com.campus.social.common.ResultCode.VERIFY_ALREADY_DONE;
import static com.campus.social.common.ResultCode.VERIFY_ALREADY_PENDING;

/**
 * 校内认证实现（F2）。
 *
 * <p><b>校验顺序不可调换</b>（ARCHITECTURE §3 Q9.2）：先判域名白名单，
 * 再写 Redis，再调 SMTP。否则任意邮箱地址会成为 Redis key 并被送去真实邮件通道，
 * 白名单就形同虚设。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampusVerifyServiceImpl implements CampusVerifyService {

    /** 验证码用途分桶。与 register / login / bind 并列，互不干扰 */
    private static final String CODE_TYPE = "campus";

    /** 审计动作名 */
    private static final String ACTION_EMAIL_BOUND = "campus_email_bound";
    private static final String ACTION_STUDENT_NO_SUBMIT = "verify_student_no_submit";
    private static final String ACTION_VERIFY_PASS = "verify_approve";

    /** verify_status */
    private static final int VERIFY_NONE = 0;
    private static final int VERIFY_OK = 1;
    private static final int VERIFY_PENDING = 2;

    /** 人工核验预期时长（小时），返回给前端做文案 */
    private static final int REVIEW_ESTIMATED_HOURS = 24;

    /** 邮箱绑定占位键的后缀。带 userId 使其可反查归属 */
    private static final String EMAIL_BIND_KEY = "campus:email:";

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final VerifyCodeService verifyCodeService;
    private final AuditService auditService;
    private final DictService dictService;
    private final CampusProperties campus;
    private final StringRedisTemplate redis;

    @Override
    @Transactional
    public SchoolProfileVO saveSchoolProfile(long userId, SchoolProfileDTO dto) {
        Long schoolId = dto.getSchoolId() == null ? campus.getSchoolId() : dto.getSchoolId();
        if (schoolId != campus.getSchoolId()) {
            // MVP 单校。放行任意 schoolId 会让用户把自己「挂」到不存在的学校，
            // 而推荐与公告全都按 school_id 过滤，结果是他既看不到公告也推荐不出人。
            throw new BusinessException(UNPROCESSABLE, "当前仅支持本校，暂时无法切换学校");
        }
        // 先校验字典 id 再落库：停用 / 不存在 / 跨校的 id 都在这一步被 422 拦掉，
        // 不会写进 user 表。名称快照由这里一并取出，避免下游再查一次字典。
        SchoolDept dept = dictService.requireSelectableDept(dto.getDeptId(), schoolId);
        SchoolGrade grade = dictService.requireSelectableGrade(dto.getGradeId());
        userMapper.updateSchoolProfile(userId, schoolId, dept.getId(), dept.getName(),
                grade.getId(), grade.getName());
        // 填了归属即推进引导进度：onboarding_step 单调递增，WHERE 已挡住回退
        userMapper.advanceOnboardingStep(userId, 1);
        log.info("school profile saved: user={}, school={}, deptId={}, gradeId={}",
                userId, schoolId, dept.getId(), grade.getId());
        return SchoolProfileVO.builder()
                .schoolId(schoolId)
                .schoolName(campus.getSchoolName())
                .deptId(dept.getId())
                .deptName(dept.getName())
                .gradeId(grade.getId())
                .gradeName(grade.getName())
                .profileComplete(true)
                .build();
    }

    @Override
    public String sendCampusEmailCode(long userId, CampusEmailSendDTO dto) {
        User me = requireUser(userId);
        if (Integer.valueOf(VERIFY_OK).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_DONE, "你已完成校内认证，无需重复认证");
        }

        String email = normalize(dto.getEmail());
        requireCampusDomain(email);
        return verifyCodeService.send(email, CODE_TYPE);
    }

    @Override
    @Transactional
    public VerifyStatusVO confirmCampusEmail(long userId, CampusEmailConfirmDTO dto) {
        User me = requireUser(userId);
        if (Integer.valueOf(VERIFY_OK).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_DONE, "你已完成校内认证，无需重复认证");
        }

        String email = normalize(dto.getEmail());
        // 确认时再判一次白名单：发送与确认之间配置可能已改，
        // 且确认是真正生效的那一步，判定必须落在这里
        requireCampusDomain(email);

        // 一次性消费：verify() 内部走 Lua 原子读删，同一码并发多次只有一次能过
        verifyCodeService.verify(email, dto.getCode(), CODE_TYPE);

        // 防「一个校园邮箱认证多个账号」（ARCHITECTURE §3 Q9.4）：
        // setIfAbsent 抢占，键里带 userId 使其可反查归属。
        // Redis 无持久化保证，最坏情况是重启后多重复绑定几次——
        // 校园邮箱本身有获取门槛，这个残余风险可接受。
        String bindKey = EMAIL_BIND_KEY + email;
        String existing = redis.opsForValue().get(bindKey);
        if (existing != null && !String.valueOf(userId).equals(existing)) {
            throw new BusinessException(CONFLICT, "该校园邮箱已绑定其他账号");
        }
        Boolean bound = redis.opsForValue().setIfAbsent(bindKey, String.valueOf(userId));
        if (!Boolean.TRUE.equals(bound) && !String.valueOf(userId).equals(existing)) {
            throw new BusinessException(CONFLICT, "该校园邮箱已绑定其他账号");
        }

        userMapper.markVerified(userId);
        auditService.record(userId, ACTION_EMAIL_BOUND, "user", userId, "email:" + email, "");
        auditService.record(userId, ACTION_VERIFY_PASS, "user", userId, "渠道:校园邮箱", "");
        log.info("campus email verified: user={}, email={}", userId, email);
        return status(userId);
    }

    @Override
    @Transactional
    public StudentNoVerifyVO submitStudentNo(long userId, StudentNoVerifyDTO dto) {
        User me = requireUser(userId);
        if (Integer.valueOf(VERIFY_OK).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_DONE, "你已完成校内认证，无需重复认证");
        }
        if (Integer.valueOf(VERIFY_PENDING).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_PENDING, "你的学号认证正在人工核验中，请耐心等待");
        }

        // school profile 是引导第 1 步，先于认证完成：
        // 没有院系年级，管理员核验时无从判断这个学号是不是本人的。
        // 判据是 id 或名称任一存在——字典化之前注册的老用户只有名称，
        // 若只判 id 会把他们永远拦在门外，且没有任何提示。
        if (!dictService.hasDept(me) || !dictService.hasGrade(me)) {
            throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE, "请先填写学校院系与年级");
        }

        String studentNo = dto.getStudentNo().trim();
        String nameTail = dto.getNameTail().trim();

        // student_no 是 UNIQUE，主防线：同一学号不能被两个账号提交
        try {
            userMapper.markVerifyPending(userId, studentNo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号提交，请确认后重试");
        }

        // name_tail 没有对应列（migration_v1.sql 第 4 节未增补），
        // 记在 audit_log：多态、只增不删，供管理员比对
        auditService.record(userId, ACTION_STUDENT_NO_SUBMIT, "user", userId,
                "学号:" + studentNo + " 姓名后缀:" + nameTail, "");
        log.info("student no submitted: user={}, studentNo={}", userId, studentNo);

        return StudentNoVerifyVO.builder()
                .verifyStatus(VERIFY_PENDING)
                .estimatedHours(REVIEW_ESTIMATED_HOURS)
                .build();
    }

    @Override
    public VerifyStatusVO status(long userId) {
        User me = requireUser(userId);
        int verifyStatus = me.getVerifyStatus() == null ? VERIFY_NONE : me.getVerifyStatus();
        boolean verified = verifyStatus == VERIFY_OK;

        return VerifyStatusVO.builder()
                .verifyStatus(verifyStatus)
                .verifyStatusText(verifyStatusText(verifyStatus))
                .schoolId(me.getSchoolId())
                // 未认证不挂学校名：避免运营号冒充校方权威（AC-12 同理）
                .schoolName(verified ? campus.getSchoolName() : null)
                .verifiedAt(verified ? verifiedAt(userId, me) : null)
                .rejectReason(verifyStatus == 3 ? rejectReason(userId) : null)
                // AC-11：两个能力都由认证状态派生，不在前端重写这套规则
                .canStrangerChat(verified)
                .canPublishAnnouncement(verified)
                .profileComplete(dictService.hasDept(me) && dictService.hasGrade(me))
                .build();
    }

    // ---------------------------------------------------------------- 私有

    private User requireUser(long userId) {
        User me = userMapper.selectById(userId);
        if (me == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(1).equals(me.getStatus())) {
            throw new BusinessException(ResultCode.USER_FROZEN, "账号已冻结");
        }
        return me;
    }

    /**
     * 域名白名单判定。
     *
     * <p>白名单为空时全部拒绝（1032）：宁可不认证，不可错认证——
     * 错认证会让任何人在校园里冒充本校学生，而这是这个功能唯一不能出的错。
     */
    private void requireCampusDomain(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "" : email.substring(at + 1);
        Set<String> whitelist = campus.resolveEmailDomains();
        if (whitelist.isEmpty()) {
            log.warn("campus email domain whitelist is empty, reject all. email={}", email);
            throw new BusinessException(CAMPUS_EMAIL_NOT_ALLOWED,
                    "尚未配置校园邮箱域名白名单，暂时无法用邮箱认证");
        }
        // 逐段匹配：白名单配 mail.xxx.edu.cn 时，xxx.edu.cn 的子域也认
        for (String allowed : whitelist) {
            if (domain.equals(allowed) || domain.endsWith("." + allowed)) {
                return;
            }
        }
        log.info("campus email domain rejected: email={}, domain={}", email, domain);
        throw new BusinessException(CAMPUS_EMAIL_NOT_ALLOWED, "该邮箱域名不是受信任的校园邮箱");
    }

    /** 邮箱统一小写：域名大小写不敏感，不统一会让 abc@X.edu 与 abc@x.edu 变成两个键 */
    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * 认证通过时间。
     *
     * <p>{@code user} 表没有 {@code verified_at} 列（migration_v1.sql 第 4 节未增补），
     * 改从 {@code audit_log} 的 {@code verify_approve} 记录反查。
     * 查不到时回退到 {@code update_time}：用户表在认证那一刻也更新过，
     * 虽不精确（后续改资料也会刷新），但好过返回 null 让前端显示「未知」。
     */
    private LocalDateTime verifiedAt(long userId, User me) {
        AuditLog row = auditLogMapper.selectLatest(ACTION_VERIFY_PASS, "user", userId);
        if (row != null && row.getCreateTime() != null) {
            return row.getCreateTime();
        }
        return me.getUpdateTime();
    }

    /** 驳回理由，同样从 audit_log 的 verify_reject 记录读 */
    private String rejectReason(long userId) {
        AuditLog row = auditLogMapper.selectLatest("verify_reject", "user", userId);
        return row == null ? null : row.getDetail();
    }

    private String verifyStatusText(int status) {
        return switch (status) {
            case VERIFY_OK -> "已认证";
            case VERIFY_PENDING -> "审核中";
            case 3 -> "认证被驳回";
            default -> "未认证";
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}