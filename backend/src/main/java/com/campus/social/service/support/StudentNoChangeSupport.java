package com.campus.social.service.support;

import com.campus.social.assembler.UserVoAssembler;
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

    /** verify_status = 1（已认证）。自动通过时用，与 UserMapper#approveVerify 的目标值一致 */
    private static final int VERIFY_OK = 1;

    /** verify_status = 2（审核中）。与 {@code resubmitAfterReject} 的 SQL 里的字面量必须一致 */
    private static final int VERIFY_PENDING = 2;

    private final UserMapper userMapper;
    /** 自动通过判定:与提交学号接口共用,避免设置页用户排队、引导页秒过的双标 */
    private final StudentNoAutoPassSupport autoPass;
    /** 六层硬校验:与 POST /user/verify/student-no 共用同一把尺子 */
    private final StudentNoProfileValidator studentNoValidator;

    /**
     * 处理一次学号变更。
     *
     * @param user       当前用户（其 {@code verifyStatus} 决定要不要迁移状态）
     * @param studentNo  新学号，已确保非空白
     * @param oldStudentNo 旧学号，仅用于日志
     */
    public void apply(User user, String studentNo, String oldStudentNo) {
        // 六层硬校验（2026-10-28）：与提交学号接口同一把尺子。
        // 设置页此前只查唯一性，格式不对/专业码对不上的学号也能进人工队列。
        studentNoValidator.validate(studentNo, user);
        // 排除自己:学号未变更时也会走到这里(见 AuthServiceImpl#updateProfile),
        // 不排除会把自己那条记录当成「已被占用」而误报 409。
        requireUnused(studentNo, user.getId());
        user.setStudentNo(studentNo);
        if (Integer.valueOf(VERIFY_REJECTED).equals(user.getVerifyStatus())) {
            resubmit(user, studentNo, oldStudentNo);
        } else {
            /*
             * 0(未认证)/1(已认证)/2(审核中)统一走「改号即重审」(2026-10-24 收敛)。
             *
             * 此前 1/2 是隐式 else:只换号落库、认证状态原样保留 ——
             * 已认证用户可以随意换学号而认证身份不失效,认证背书形同虚设。
             * 现在与状态 0 同待遇:markVerifyPending 重新进核验队列
             * (SQL 无状态前置,1→2 也成立),自动通过条件满足则直接置 1。
             *
             * 「未认证填学号=提交核验」的原注释(WJ 实测状态纹丝不动的那次)
             * 语义不变;内存对象必须同步,否则紧随其后的 updateById
             * 会把状态覆盖回去(上一轮死循环 bug 的同款坑)。
             */
            userMapper.markVerifyPending(user.getId(), studentNo);
            if (autoPass.canAutoPass(studentNo, user)) {
                userMapper.approveVerify(user.getId());
                user.setVerifyStatus(VERIFY_OK);
                log.info("verify auto approved by profile edit: user={}, studentNo={}",
                        user.getId(), UserVoAssembler.maskUsername(studentNo));
            } else {
                user.setVerifyStatus(VERIFY_PENDING);
            }
            log.info("verify (re)submitted by profile edit: user={}, studentNo={}",
                    user.getId(), UserVoAssembler.maskUsername(studentNo));
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
    /**
     * 学号唯一性校验。
     *
     * @param selfId 当前用户 id,**必须排除自己**:学号未变更时也会走到这里,
     *               不排除会把自己那条记录当成「已被占用」而误报 409。
     */
    private void requireUnused(String studentNo, long selfId) {
        Long dup = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStudentNo, studentNo)
                        .ne(User::getId, selfId));
        if (dup != null && dup > 0) {
            throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号绑定");
        }
    }

    /**
     * 3 → 2，重新进待审队列。
     *
     * <p><b>关键：必须同步内存对象的 verifyStatus</b>。
     * 调用链是 {@code AuthServiceImpl.updateProfile}：
     * <pre>
     *   apply(...)              <- 本方法在这里把 DB 的 verify_status 写成 2
     *   userMapper.updateById(user)  <- 随后把整个实体写回
     * </pre>
     * MyBatis-Plus 默认 {@code NOT_NULL} 更新策略：实体里非 null 的字段都会进 SET。
     * 若内存对象仍是 {@code verifyStatus=3}，这行updateById 就会把刚写进去的 2
     * <b>覆盖回 3</b> —— 接口返回 200、student_no 也是新值，唯独状态没变，
     * 用户看到「已保存」却发现提示条还挂着、进不了待审队列，
     * 就是本轮要修的那个死循环本身。
     *
     * <p><b>为什么不在 updateById 之前绕开它</b>（另两种方案都试过取舍）：
     * <ul>
     *   <li>给 {@code verifyStatus} 加 {@code @TableField(updateStrategy = NEVER)}
     *       —— 会让**所有** updateById 都不再更新该字段，将来若有别处依赖
     *       「改资料顺带更新认证状态」会静默失效，且这个副作用发生在实体上、全局可见。
     *   <li>学号变更时跳过 updateById 走独立 UPDATE —— 把一条 UPDATE 拆成两条，
     *       多一次往返，且「昵称头像与学号」不再原子，中途失败会留下半更新状态。
     * </ul>
     * 同步内存对象是三者里唯一保持单条 SQL、且副作用局限在本方法内的做法。
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
        // 与上面的 SQL 保持一致，否则紧随其后的 updateById 会把状态覆盖回去。
        user.setVerifyStatus(VERIFY_PENDING);
        log.info("verify resubmitted by profile edit: user={}, oldStudentNo={}, newStudentNo={}",
                user.getId(), UserVoAssembler.maskUsername(oldStudentNo),
                UserVoAssembler.maskUsername(newStudentNo));
    }
}