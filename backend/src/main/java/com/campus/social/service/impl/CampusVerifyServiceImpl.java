package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.dto.CampusEmailConfirmDTO;
import com.campus.social.dto.CampusEmailSendDTO;
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
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.StudentNoAutoPassSupport;
import com.campus.social.util.StudentNoParser;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoParsedVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.CAMPUS_EMAIL_NOT_ALLOWED;
import static com.campus.social.common.ResultCode.CONFLICT;
import static com.campus.social.common.ResultCode.SCHOOL_PROFILE_INCOMPLETE;
import static com.campus.social.common.ResultCode.STUDENT_NO_MISMATCH;
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

    /**
     * 学号编码规则(由 5 个真实样本锁定,用户确认):
     * <pre>
     *   位 1-2   入学年份            23 -> 2023
     *   位 3-6   专业大类编码        0809=计算机类 0802=机械类 0818=交通运输类
     *   位 7-8   专业位号            类内序号
     *   位 9-10  班级                只可能是 0X(01~09,没有超过 9 个班)
     *   位 11-12 层次标记            23 级本科「05」/专科「06」两位(14 位学号);
     *                                24/25/26 级本科「5」/专科「6」一位(13 位学号)
     *   最后 2 位 班内序号           取值 00~69(本科/专科同此范围)
     * </pre>
     * 用 {@code 0?5} 同时表达两种:14 位匹配「05」、13 位匹配「5」。
     * 与 {@link StudentNoParser}(解析展示用)不同:这里参与提交拦截,
     * 班级/排名/固定标识是用户确认的硬约束,必须挡在人工队列之外。
     */
    private static final Pattern STUDENT_NO_FORMAT =
            Pattern.compile("^(\\d{2})(\\d{4})(\\d{2})(0[1-9])0?[56](\\d{2})$");

    /** 班内序号上限:用户明确「最后两位取值 00~69」 */
    private static final int MAX_CLASS_RANK = 69;

    /** 自动通过审计动作。与管理员手动通过的 verify_approve 区分,便于事后抽查 */
    private static final String ACTION_VERIFY_AUTO_APPROVE = "verify_auto_approve";

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
    /** 自动通过判定。与设置页改学号(StudentNoChangeSupport)共用同一把尺子 */
    private final StudentNoAutoPassSupport autoPass;
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
        // 专业必须属于所选学院：选了 A 学院却提交 B 学院的专业 id，会写出
        // 「学院与专业对不上」的脏数据，后续学号专业码校验也会失真。
        SchoolMajor major = dictService.requireSelectableMajor(dto.getMajorId(), dept.getId());
        userMapper.updateSchoolProfile(userId, schoolId, dept.getId(), dept.getName(),
                grade.getId(), grade.getName(), major.getId());
        // 填了归属即推进引导进度：onboarding_step 单调递增，WHERE 已挡住回退
        userMapper.advanceOnboardingStep(userId, 1);
        log.info("school profile saved: user={}, school={}, deptId={}, gradeId={}, majorId={}",
                userId, schoolId, dept.getId(), grade.getId(), major.getId());
        return SchoolProfileVO.builder()
                .schoolId(schoolId)
                .schoolName(campus.getSchoolName())
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
        log.info("campus email verified: user={}, email={}", userId, UserVoAssembler.maskUsername(email));
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
        if (me.getMajorId() == null) {
            // 引导第 1 步要求同时填学院/本专科/年级/专业,六层的专业编码匹配强依赖 majorId。
            // 老用户字典化前只有名称没有 id,若缺 id 先挡回去,不要带着空专业进六层比对。
            throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE, "请先填写专业");
        }

        String studentNo = dto.getStudentNo().trim();
        String nameTail = dto.getNameTail().trim();

        // 六层硬校验:纯数字 / 长度 / 年级 / 层次 / 专业编码 / 班内序号。
        // 任一不过直接拒绝,不再转人工——否则「学院乱选+学号不符」的脏数据
        // 会进人工队列浪费审核时间。归属在上方已确认填写,这里拿实体做交叉比对。
        validateStudentNoAgainstProfile(studentNo, me);

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
        log.info("student no submitted: user={}, studentNo={}", userId, UserVoAssembler.maskUsername(studentNo));

        /*
         * 自动通过(格式 + 院系交叉校验都过):不再让真实用户排队等人工。
         * 先落待审再立刻置已认证 —— approveVerify 的 WHERE 只认状态 2,
         * 走状态机才与管理员手动通过留下同样的审计与状态轨迹。
         */
        if (autoPass.canAutoPass(studentNo, me)) {
            int affected = userMapper.approveVerify(userId);
            if (affected > 0) {
                auditService.record(userId, ACTION_VERIFY_AUTO_APPROVE, "user", userId,
                        "学号:" + studentNo + " 渠道:自动通过(格式与院系交叉校验一致)", "");
                log.info("student no auto approved: user={}, studentNo={}", userId, UserVoAssembler.maskUsername(studentNo));
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
                // 未认证不挂学校名：避免运营号冒充校方权威（AC-12 同理）
                .schoolName(verified ? campus.getSchoolName() : null)
                .verifiedAt(verified ? verifiedAt(userId, me) : null)
                .rejectReason(verifyStatus == 3 ? rejectReason(userId) : null)
                // AC-11：两个能力都由认证状态派生，不在前端重写这套规则
                .canStrangerChat(verified)
                .canPublishAnnouncement(verified)
                .profileComplete(dictService.hasDept(me) && dictService.hasGrade(me))
                // 本人学号:设置页靠它回填学号框 —— 否则回填为空、框里只剩
                // placeholder,用户以为「学号已经有了」直接点保存,实际提交的是
                // 空字符串,后端不触发核验(WJ 实测:点保存无数次状态纹丝不动)。
                .studentNo(me.getStudentNo())
                .build();
    }

    // ---------------------------------------------------------------- 私有

    /**
     * 学号与本人学校归属的六层硬校验（注册-认证一致性兜底）。
     *
     * <p><b>为什么是硬校验而不是转人工</b>：这六层全是基于字典的确定性比对,
     * 不通过就是「填错」而非「待核实」。把它挡在 {@code autoPass} 之前,
     * 乱填的学号根本不会进人工队列,管理员队列里只剩真正需要肉眼比对的 case。
     *
     * <p><b>六层顺序</b>（与注册页前端 validator 同一口径,前端先拦、后端兜底）：
     * <ol>
     *   <li>纯数字：{@code ^\d+$}</li>
     *   <li>长度：23 级 14 位、24 级起 13 位（当前规格列 23/24/25/26,24+ 统一 13 位）</li>
     *   <li>年级匹配：学号前两位 == 所选年级入学年份后两位</li>
     *   <li>层次匹配：学号层次标记(本科 05/5、专科 06/6) == 所选专业层次</li>
     *   <li>专业编码匹配：学号位 3-8（6 位）== 所选专业 code</li>
     *   <li>班内序号：学号最后两位 < 70</li>
     * </ol>
     *
     * <p>调用方需保证 {@code me} 的 deptId / gradeId / majorId 均已填写
     * （{@code submitStudentNo} 已在上方拦截未填的情况）。
     */
    private void validateStudentNoAgainstProfile(String studentNo, User me) {
        SchoolMajor major = dictService.requireSelectableMajor(me.getMajorId(), me.getDeptId());
        SchoolGrade grade = dictService.requireSelectableGrade(me.getGradeId());
        if (major.getCode() == null || major.getCode().length() != 6) {
            // 字典数据缺陷,不是用户填错:交人工复核而不是拿一个坏 code 比,避免误拒真学生
            throw new BusinessException(UNPROCESSABLE, "专业编码缺失，请联系管理员核对专业字典");
        }

        // 第 1 层：纯数字
        if (!studentNo.matches("\\d+")) {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号需为纯数字");
        }
        // 第 2 层：长度与入学年份匹配
        int year = Integer.parseInt(studentNo.substring(0, 2));
        int len = studentNo.length();
        if (len == 14) {
            if (year != 23) {
                throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度与入学年份不匹配（23 级为 14 位）");
            }
        } else if (len == 13) {
            if (year < 24) {
                throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度与入学年份不匹配（24 级起为 13 位）");
            }
        } else {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度须为 13 或 14 位");
        }
        // 第 3 层：年级匹配（第 3~6 层细节只进日志，见 mismatch 的注释）
        int gradeSuffix = grade.getEnrollYear() % 100;
        if (year != gradeSuffix) {
            mismatch(me, "L3 年级", "学号前两位=" + year + " vs 年级入学年份后两位=" + gradeSuffix);
        }
        // 第 4 层：层次匹配（学号层次标记 == 所选专业层次）
        int marker = parseLevelMarker(studentNo);
        if (marker != major.getLevel()) {
            mismatch(me, "L4 层次", "学号层次标记=" + marker + " vs 专业层次=" + major.getLevel());
        }
        // 第 5 层：专业编码匹配（学号位 3-8 == 所选专业 code）
        String majorCode = studentNo.substring(2, 8);
        if (!majorCode.equals(major.getCode())) {
            mismatch(me, "L5 专业码", "学号位3-8=" + majorCode + " vs 专业 code=" + major.getCode());
        }
        // 第 6 层：班内序号 < 70
        int seq = Integer.parseInt(studentNo.substring(len - 2));
        if (seq > MAX_CLASS_RANK) {
            mismatch(me, "L6 班内序号", "末两位=" + seq + " 上限=" + MAX_CLASS_RANK);
        }
    }

    /**
     * 第 3~6 层的统一失败出口：对外一句「不匹配」，细节进日志。
     *
     * <p><b>为什么必须收敛</b>(2026-10-19 安全修正)：这四层分别泄露
     * 「前 2 位=入学年份」「层次标记=05/5 或 06/6」「位 3-8=专业 code」
     * 「末两位&lt;70」。逐层抛出具体原因等于把学号编码规则逐条讲给攻击者——
     * 拿到一个学号后逐条试错，五次之内即可复原整套规则，六层随即形同虚设。
     * 这正是六层的设计意图(拦住"只知道学号、不知道规则"的人)被自己破坏的例子。
     *
     * <p>第 1/2 层(纯数字 / 长度)不收敛：规则已在 {@code RegisterDTO} 的
     * {@code ^\d{13,14}$} 正则里公开，不构成额外泄露，且保留文案对真人更友好。
     *
     * <p>log 带 userId、层级与比对值，便于管理员在后台核对队列反查是谁、哪一层没过。
     * 学号进日志是安全的（管理员本来就能在库里看到），对外才是敏感的。
     */
    private void mismatch(User me, String layer, String detail) {
        log.warn("studentNo verify rejected: user={}, layer={}, detail={}",
                me.getId(), layer, detail);
        throw new BusinessException(STUDENT_NO_MISMATCH);
    }

    /**
     * 解析学号层次标记为本/专科枚举。
     *
     * @return 1=本科 2=专科 0=无法识别（14 位取位 11-12,13 位取位 11）
     */
    private int parseLevelMarker(String studentNo) {
        String marker = studentNo.length() == 14
                ? studentNo.substring(10, 12)
                : studentNo.substring(10, 11);
        if ("05".equals(marker) || "5".equals(marker)) return 1;
        if ("06".equals(marker) || "6".equals(marker)) return 2;
        return 0;
    }

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
            log.warn("campus email domain whitelist is empty, reject all. email={}", UserVoAssembler.maskUsername(email));
            throw new BusinessException(CAMPUS_EMAIL_NOT_ALLOWED,
                    "尚未配置校园邮箱域名白名单，暂时无法用邮箱认证");
        }
        // 逐段匹配：白名单配 mail.xxx.edu.cn 时，xxx.edu.cn 的子域也认
        for (String allowed : whitelist) {
            if (domain.equals(allowed) || domain.endsWith("." + allowed)) {
                return;
            }
        }
        /*
         * email 脱敏、domain 不脱敏（2026-10-21）。
         * 域名本身不是个人敏感信息，而且它是排查「哪家学校的邮箱被拒」的唯一线索 ——
         * 把它打码等于把这条日志最有用的部分删掉。
         * （前一步的批量替换误伤了 domain，这里改回来。）
         */
        log.info("campus email domain rejected: email={}, domain={}",
                UserVoAssembler.maskUsername(email), domain);
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