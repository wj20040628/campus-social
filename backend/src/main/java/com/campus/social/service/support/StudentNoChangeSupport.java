package com.campus.social.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.campus.social.common.ResultCode.STUDENT_NO_EXISTS;

/**
 * 改学号这件事的完整判定（唯一校验 + 核验状态迁移）。
 *
 * <p><b>为什么从 {@code AuthServiceImpl.updateProfile} 拆出来</b>：
 * 那边管「资料怎么写、审计怎么记」，这里管「学号能不能换、换了认证状态怎么办」。
 * 后者是本轮修的死循环所在、也是最容易被后续改动踩坏的一段
 * （它必须同时守住「已认证用户不被拉回审核」这个边界），
 * 混在昵称头像的赋值逻辑里既撑爆了那个文件的行数，也让 reviewer 一眼看不出来。
 *
 * <p><b>修的是什么死循环</b>：原先改学号只写 {@code student_no} 不碰
 * {@code verify_status}。于是被驳回（3）的用户即使换了学号，状态仍停在 3，
 * 新学号永远进不了待审队列；而管理员一个人在学校里不会知道有人在等他。
 * 前端提示条的「去改学号」按钮点了等于没点——界面承诺了出口，出口是死的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentNoChangeSupport {

    /**
     * verify_status = 3（已驳回）。
     *
     * <p>写成常量而非魔法数字：这个值错一位就是线上事故——
     * 写成 2 会让已认证用户被拽回审核队列（他们会突然失去发公告与搭话权限且无提示），
     * 写成 1 会让被驳回的用户直接拿到认证权限。
     */
    private static final int VERIFY_REJECTED = 3;

    private final UserMapper userMapper;

    /**
     * 处理一次学号变更。
     *
     * @param user       当前用户（其 {@code verifyStatus} 决定要不要迁移状态）
     * @param studentNo  新学号，已确保非空白且与旧值不同
     * @param oldStudentNo 旧学号，仅用于日志
     */
    public void apply(User user, String studentNo, String oldStudentNo) {
        requireUnused(studentNo);
        user.setStudentNo(studentNo);
        if (Integer.valueOf(VERIFY_REJECTED).equals(user.getVerifyStatus())) {
            resubmit(user, studentNo, oldStudentNo);
        }
    }

    /**
     * 学号唯一性校验。
     *
     * <p><b>抛 409 而不是静默跳过</b>（本轮明确的决策）：
     * 静默跳过会让用户看到「已保存」，却发现状态还是「审核中」、提示条还挂着，
     * 他会以为自己没保存成功而反复点，或者以为系统坏了——
     * 当场报错让他立刻知道该换一个号。契约也确实这么要求
     * （{@code PUT /user/me} 的 studentNo 描述写明「重复返回 STUDENT_NO_EXISTS」）。
     *
     * <p>{@code uk_student_no} 是库级单列唯一索引，这里是应用层的提前拦截：
     * 让它回一条能读懂的业务错误，而不是把底层的 DuplicateKeyException 冒到前端。
     */
    private void requireUnused(String studentNo) {
        Long dup = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStudentNo, studentNo));
        if (dup != null && dup > 0) {
            throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号绑定");
        }
    }

    /**
     * 3 → 2，重新进待审队列。
     *
     * <p><b>旧驳回理由就此失效，且不需要额外删除 audit_log</b>：
     * {@code CampusVerifyServiceImpl.status()} 只在 {@code verifyStatus == 3}
     * 时才去读 {@code verify_reject} 记录，状态一变它自然读不到。
     * 前端 {@code showRejected} 的判据是
     * {@code verifyStatus===3 && rejectReason非空}，两个条件同时不成立，提示条消失。
     * audit_log 只增不删是它的设计（多态留痕），不该为了前端展示去删历史。
     */
    private void resubmit(User user, String newStudentNo, String oldStudentNo) {
        // WHERE 带 verify_status = 3：已认证(1) 与审核中(2) 都不会被拽回队列。
        // 这是本轮最要紧的边界，已认证用户改个昵称资料不该被重新拉回审核。
        userMapper.resubmitAfterReject(user.getId(), newStudentNo);
        log.info("verify resubmitted by profile edit: user={}, oldStudentNo={}, newStudentNo={}",
                user.getId(), oldStudentNo, newStudentNo);
    }
}