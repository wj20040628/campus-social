package com.campus.social.service.impl;

import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AdminVerifyService;
import com.campus.social.service.AuditService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.AdminVerifyPageVO;
import com.campus.social.vo.AdminVerifyPendingItemVO;
import com.campus.social.vo.StudentNoParsedVO;
import com.campus.social.util.StudentNoParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.VERIFY_NOT_FOUND;

/**
 * 校园信息核对队列实现。
 *
 * <p><b>定位（2026-10-30 起）</b>：这里的「通过 / 拒绝」只决定
 * {@code verify_status} 是 1 还是 3，<b>不再授予或收回任何功能</b>。
 * 权限门槛已改由 {@code CampusAccessService} 按账号年龄与信誉分判定，
 * 因此本队列积压不会导致任何人无法发帖、搭话或建圈。
 *
 * <p><b>队列就是 user 表本身</b>：不为核对新建表。
 * {@code verify_status = 2} 的行天然构成待审队列，
 * 新建一张 review 表要同步维护两张表的状态一致性，
 * 而「谁提交了学号」这个问题 user 表已经回答过了。
 *
 * <p><b>为什么还要人工核对</b>：学号与所选年级/专业的一致性
 * 由 {@link com.campus.social.service.support.StudentNoConsistencyChecker}
 * 自动比对，对不上的才进队列。学校不提供学生名单，
 * 所以这里核对的是「与字典能否对上」，而不是「这个人是否真是该校学生」——
 * 结论只用于资料展示，不能被当作身份证明。
 *
 * <p><b>审批动作名不可自造</b>：{@code verify_approve} 与 {@code verify_reject}
 * 是 {@code CampusVerifyServiceImpl} 里 {@code verifiedAt()} 与
 * {@code rejectReason()} 反查 audit_log 时用的键。
 * 写成别的名字，用户在 {@code GET /user/verify/status} 里就永远看不到
 * 驳回理由和登记时间。
 *
 * <p><b>学号解析结果不参与审批</b>：{@link StudentNoParser} 的输出只挂在
 * {@code AdminVerifyPendingItemVO.studentNoParsed} 上供展示，
 * approve / reject 两个方法都不读它。解析规则是从 5 个样本反推的、
 * 尚未在全校学号上验证过，若拿它当审批依据，规则一旦与真实编码不符
 * 就会系统性拒掉某一届学生。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminVerifyServiceImpl implements AdminVerifyService {

    /** 审核动作名。与 CampusVerifyServiceImpl 的反查键严格一致，改名即断链 */
    private static final String ACTION_VERIFY_PASS = "verify_approve";
    private static final String ACTION_VERIFY_REJECT = "verify_reject";

    /** 通知类型：16 校园信息已登记 / 17 校园信息需修改（理由放 content） */
    private static final int NOTIFY_VERIFY_PASS = 16;
    private static final int NOTIFY_VERIFY_REJECT = 17;

    /** verify_status = 2 审核中 */
    private static final int VERIFY_PENDING = 2;

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final RoleService roleService;
    private final PostNotifySupport notifySupport;

    @Override
    public AdminVerifyPageVO pending(Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        // 多取一条判 hasMore，与 AdminReportPage 的游标实现同构
        List<AdminVerifyPendingItemVO> rows = userMapper.selectPendingVerify(cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AdminVerifyPendingItemVO> page = hasMore ? rows.subList(0, limit) : rows;
        fillParsed(page);

        AdminVerifyPageVO vo = new AdminVerifyPageVO();
        vo.setList(page);
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getUserId() : null);
        return vo;
    }

    @Override
    @Transactional
    public void approve(long adminId, long userId) {
        // 拦截器的 @RequireAdmin 只覆盖 HTTP 入口；
        // 这里是防「绕过 Controller 直调 service」的兜底
        roleService.requireAdmin(adminId);
        User target = requirePending(userId);

        int affected = userMapper.approveVerify(userId);
        if (affected == 0) {
            // 前一个判据已确认状态是 2，走到这里只可能是另一个管理员刚处置过。
            // 回 404 让它刷新列表，而不是静默成功——静默成功会让他以为批过了
            throw new BusinessException(VERIFY_NOT_FOUND, "该校园信息核对申请不存在或已不在核对中");
        }
        auditService.record(adminId, ACTION_VERIFY_PASS, "user", userId,
                "渠道:校园信息 学号:" + nullToEmpty(target.getStudentNo()), "");
        // 通过也要通知：此前用户只能自己进「编辑资料」猜结果
        notifySupport.pushVerifyResult(userId, NOTIFY_VERIFY_PASS, null);
        log.info("verify approved: user={}, admin={}", userId, adminId);
    }

    @Override
    @Transactional
    public void reject(long adminId, long userId, String reason) {
        roleService.requireAdmin(adminId);
        User target = requirePending(userId);

        int affected = userMapper.rejectVerify(userId);
        if (affected == 0) {
            throw new BusinessException(VERIFY_NOT_FOUND, "该校园信息核对申请不存在或已不在核对中");
        }
        // detail 直接存 reason 原串：status() 的 rejectReason() 读的就是 detail，
        // 拼前缀会让用户看到「驳回理由: xxx」这种二次包装的文案
        auditService.record(adminId, ACTION_VERIFY_REJECT, "user", userId, reason, "");
        // 驳回理由必须送到用户手里，否则「驳回」等于静默失败——用户只会反复重提
        notifySupport.pushVerifyResult(userId, NOTIFY_VERIFY_REJECT,
                reason == null ? null : reason.trim());
        log.info("verify rejected: user={}, admin={}, reasonLen={}", userId, adminId,
                reason == null ? 0 : reason.length());
    }

    // ---------------------------------------------------------------- 私有

    /**
     * 给每一条补上学号解析结果。
     *
     * <p><b>就地改，不另查库</b>：解析只吃已取回的 studentNo 字符串，
     * 一页最多 50 条，全是内存里的正则匹配。为此多发一次 DB 往返
     * 去「把学号再读一遍」是纯浪费。
     *
     * <p>逐条 set而不是 {@code rows.replaceAll}：{@code subList} 是视图，
     * 对它做结构性替换会抛 {@code UnsupportedOperationException}，
     * 而这个异常只在 hasMore 为 true（即正好有第二页）时才触发——
     * 第一页永远正常，翻页时才崩。
     */
    private void fillParsed(List<AdminVerifyPendingItemVO> rows) {
        for (AdminVerifyPendingItemVO row : rows) {
            StudentNoParsedVO parsed = StudentNoParser.parse(row.getStudentNo());
            row.setStudentNoParsed(parsed);
            row.setLevel(levelText(row.getMajorLevel()));
            row.setGradeMatch(gradeMatch(parsed, row.getGradeEnrollYear()));
            row.setMajorMatch(majorMatch(parsed, row.getMajorCode()));
        }
    }

    /** 字典层次 → 文案。未知值回 null，不猜 */
    private static String levelText(Integer level) {
        if (level == null) return null;
        if (level == 1) return "本科";
        if (level == 2) return "专科";
        return null;
    }

    /**
     * 年级比对：字典 enroll_year 与学号前两位（2000 + 两位）直接比。
     * 任一侧缺失回 null——「不知道」不能显示成「不一致」。
     */
    private static Boolean gradeMatch(StudentNoParsedVO parsed, Integer enrollYear) {
        if (parsed == null || !parsed.isParsed() || parsed.getEntranceYear() == null
                || enrollYear == null) {
            return null;
        }
        return parsed.getEntranceYear().equals(enrollYear);
    }

    /** 专业比对：字典 6 位专业码与学号位 3-8 直接比，语义同上 */
    private static Boolean majorMatch(StudentNoParsedVO parsed, String dictCode) {
        if (parsed == null || !parsed.isParsed() || parsed.getMajorCode() == null
                || dictCode == null || dictCode.isBlank()) {
            return null;
        }
        return parsed.getMajorCode().equals(dictCode.trim());
    }

    /**
     * 断言目标存在且处于「审核中」。
     *
     * <p>不复用 {@code CampusVerifyServiceImpl} 里的 {@code requireUser}：
     * 那个方法额外拦「已冻结」账号（USER_FROZEN），而审核场景下
     * 冻结中的账号也必须能被审批——否则管理员永远清不掉这批人的待审记录。
     */
    private User requirePending(long userId) {
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(VERIFY_NOT_FOUND, "该校园信息核对申请不存在或已不在核对中");
        }
        if (!Integer.valueOf(VERIFY_PENDING).equals(target.getVerifyStatus())) {
            throw new BusinessException(VERIFY_NOT_FOUND, "该校园信息核对申请不存在或已不在核对中");
        }
        return target;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}