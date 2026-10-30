package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.SchoolMajor;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.CampusVerifyService;
import com.campus.social.service.DictService;
import com.campus.social.service.support.StudentNoConsistencyChecker;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.campus.social.common.ResultCode.SCHOOL_PROFILE_INCOMPLETE;
import static com.campus.social.common.ResultCode.STUDENT_NO_EXISTS;
import static com.campus.social.common.ResultCode.STUDENT_NO_MISMATCH;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;
import static com.campus.social.common.ResultCode.VERIFY_ALREADY_DONE;
import static com.campus.social.common.ResultCode.VERIFY_ALREADY_PENDING;

/**
 * 校园信息登记实现。
 *
 * <p><b>2026-10-30 起不再是权限门槛</b>：这里写入的 {@code verify_status}
 * 只表示「校园信息是否登记 / 是否与字典一致」，不参与发帖、搭话、建圈、
 * 集市发布等任何权限判定（门槛见 {@code CampusAccessService}）。
 * 学校不提供学生名单，本系统无法证明「某人是某校学生」，
 * 因此不把它当身份认证对外宣传。
 *
 * <p><b>2026-10-30 同步删除校园邮箱验证码路径</b>：学生没有学校发放的
 * 校园邮箱，现有邮箱是 QQ / 网易等个人邮箱，域名白名单只会误伤。
 * 校园信息只保留学号一致性比对 + 管理员人工核对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampusVerifyServiceImpl implements CampusVerifyService {

    /** 审计动作名 */
    private static final String ACTION_STUDENT_NO_SUBMIT = "verify_student_no_submit";
    private static final String ACTION_VERIFY_PASS = "verify_approve";

    /** 自动通过审计动作。与管理员手动通过的 verify_approve 区分,便于事后抽查 */
    private static final String ACTION_VERIFY_AUTO_APPROVE = "verify_auto_approve";

    /** verify_status */
    private static final int VERIFY_NONE = 0;
    private static final int VERIFY_OK = 1;
    private static final int VERIFY_PENDING = 2;

    /** 人工核验预期时长（小时），返回给前端做文案 */
    private static final int REVIEW_ESTIMATED_HOURS = 24;

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditService auditService;
    private final DictService dictService;
    private final CampusProperties campus;
    /** 一致性检查：只决定直接登记还是转管理员核对，不拒绝用户 */
    private final StudentNoConsistencyChecker consistency;

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
        // 专业必须属于所选学院：选了 A 学院却提交 B 学院的专业 id，会写出
        // 「学院与专业对不上」的脏数据，后续学号专业码校验也会失真。
        SchoolMajor major = dictService.requireSelectableMajor(dto.getMajorId(), dept.getId());
        /*
         * 改归属即重置登记状态(2026-10-24 起,2026-10-30 调整措辞):
         * 学号一致性是相对学院/年级/专业比对的,归属变了旧结论对新组合不再成立,
         * 因此把状态退回未填写(0),由用户按新归属重新提交学号。
         *
         * 只在**归属真的变了**时重置:原样保存不该把登记状态清掉。
         * 核对中(2)改归属同样退回:管理员不该核对一个新旧混合的组合。
         *
         * 注意这只影响本人资料上的标记,不影响任何功能——
         * 校园信息已退出权限判定。
         */
        User owner = userMapper.selectById(userId);
        boolean identityChanged = owner != null
                && (Integer.valueOf(1).equals(owner.getVerifyStatus())
                    || Integer.valueOf(VERIFY_PENDING).equals(owner.getVerifyStatus()))
                && (!java.util.Objects.equals(owner.getDeptId(), dept.getId())
                    || !java.util.Objects.equals(owner.getGradeId(), grade.getId())
                    || !java.util.Objects.equals(owner.getMajorId(), major.getId()));
        if (identityChanged) {
            userMapper.updateVerifyStatus(userId, VERIFY_NONE);
            auditService.record(userId, "verify_reset_by_profile_change", "user", userId,
                    "已登记/核对中用户修改学校归属,校园信息状态重置为未填写", "");
            log.info("verify reset by school-profile change: user={}", userId);
        }
        /*
         * 校区(2026-10-25):1=东 2=西,可选。与院系完全解耦(东西校区都有全部院系),
         * **刻意不进 identityChanged 判定**——校区不参与学号一致性比对,
         * 换校区不该把登记状态清掉。
         */
        Integer campusVal = dto.getCampus();
        if (campusVal != null && campusVal != 1 && campusVal != 2) {
            throw new BusinessException(UNPROCESSABLE, "校区取值不合法(1=东校区 2=西校区)");
        }
        userMapper.updateSchoolProfile(userId, schoolId, campusVal, dept.getId(), dept.getName(),
                grade.getId(), grade.getName(), major.getId());
        // 填了归属即推进引导进度：onboarding_step 单调递增，WHERE 已挡住回退
        userMapper.advanceOnboardingStep(userId, 1);
        log.info("school profile saved: user={}, school={}, campus={}, deptId={}, gradeId={}, majorId={}",
                userId, schoolId, campusVal, dept.getId(), grade.getId(), major.getId());
        return SchoolProfileVO.builder()
                .schoolId(schoolId)
                .schoolName(campus.getSchoolName())
                .campus(campusVal)
                .deptId(dept.getId())
                .deptName(dept.getName())
                .gradeId(grade.getId())
                .gradeName(grade.getName())
                .majorId(major.getId())
                .majorName(major.getName())
                .profileComplete(true)
                .build();
    }

    @Override
    @Transactional
    public StudentNoVerifyVO submitStudentNo(long userId, StudentNoVerifyDTO dto) {
        User me = requireUser(userId);
        if (Integer.valueOf(VERIFY_OK).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_DONE, "校园信息已登记，无需重复提交");
        }
        if (Integer.valueOf(VERIFY_PENDING).equals(me.getVerifyStatus())) {
            throw new BusinessException(VERIFY_ALREADY_PENDING, "校园信息正在核对中，请耐心等待");
        }

        // school profile 是引导第 1 步，先于学号提交完成：
        // 没有院系年级，就无法把学号里的年级/专业码与所选归属做一致性比对。
        // 判据是 id 或名称任一存在——字典化之前注册的老用户只有名称，
        // 若只判 id 会把他们永远拦在门外，且没有任何提示。
        if (!dictService.hasDept(me) || !dictService.hasGrade(me)) {
            throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE, "请先填写学校院系与年级");
        }
        if (me.getMajorId() == null) {
            // 引导第 1 步要求同时填学院/本专科/年级/专业,专业编码比对依赖 majorId。
            // 老用户字典化前只有名称没有 id,若缺 id 先挡回去,不要带着空专业进比对。
            throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE, "请先填写专业");
        }

        String studentNo = dto.getStudentNo().trim();
        String nameTail = dto.getNameTail() == null ? "" : dto.getNameTail().trim();

        /*
         * 2026-10-30：只拦格式与重复，不再拿年级/层次/专业编码硬拒。
         * 校园信息已经不是权限门槛，规则误判不该让用户连资料都存不上；
         * 一致性结果只决定「直接登记」还是「进管理员核对队列」。
         */
        if (!studentNo.matches("\\d{13,14}")) {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号需为 13~14 位数字");
        }

        // student_no 是 UNIQUE，主防线：同一学号不能被两个账号提交
        try {
            userMapper.markVerifyPending(userId, studentNo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号提交，请确认后重试");
        }

        // name_tail 没有对应列（migration_v1.sql 第 4 节未增补），
        // 记在 audit_log：多态、只增不删，供管理员比对
        auditService.record(userId, ACTION_STUDENT_NO_SUBMIT, "user", userId,
                "学号:" + studentNo + (nameTail.isEmpty() ? "" : " 姓名后缀:" + nameTail), "");
        log.info("student info submitted: user={}, studentNo={}", userId, UserVoAssembler.maskUsername(studentNo));

        /*
         * 一致 → 直接置 1（已登记）；不一致 → 留在 2（核对中）由管理员看。
         * 两条路径都不影响任何功能，只影响本人设置页的标记。
         */
        if (consistency.matches(studentNo, me)) {
            int affected = userMapper.approveVerify(userId);
            if (affected > 0) {
                auditService.record(userId, ACTION_VERIFY_AUTO_APPROVE, "user", userId,
                        "学号:" + studentNo + " 渠道:格式与院系一致，直接登记", "");
                log.info("campus info registered: user={}, studentNo={}", userId, UserVoAssembler.maskUsername(studentNo));
                return StudentNoVerifyVO.builder()
                        .verifyStatus(VERIFY_OK)
                        .autoApproved(true)
                        .build();
            }
        }

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
                // 没填学校就不挂校名；已填则回显，与登记状态无关
                .schoolName(me.getSchoolId() == null ? null : campus.getSchoolName())
                .verifiedAt(verified ? verifiedAt(userId, me) : null)
                .rejectReason(verifyStatus == 3 ? rejectReason(userId) : null)
                .profileComplete(dictService.hasDept(me) && dictService.hasGrade(me))
                // 本人学号:设置页靠它回填学号框 —— 否则回填为空、框里只剩
                // placeholder,用户以为「学号已经有了」直接点保存,实际提交的是
                // 空字符串,后端不触发核验(WJ 实测:点保存无数次状态纹丝不动)。
                .studentNo(me.getStudentNo())
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
     * 校园信息登记时间。
     *
     * <p>{@code user} 表没有 {@code verified_at} 列（migration_v1.sql 第 4 节未增补），
     * 改从 {@code audit_log} 的 {@code verify_approve} 记录反查。
     * 查不到时回退到 {@code update_time}：用户表在登记那一刻也更新过，
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
            case VERIFY_OK -> "校园信息已登记";
            case VERIFY_PENDING -> "校园信息核对中";
            case 3 -> "校园信息需修改";
            default -> "校园信息未填写";
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}