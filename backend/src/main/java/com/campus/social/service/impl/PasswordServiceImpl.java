package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.SetPasswordDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.PasswordService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.TokenService;
import com.campus.social.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.*;

/**
 * 密码生命周期：找回、重置、修改与首次设密。
 *
 * <p>所有会改变密码的路径都会吊销该账号的 refresh 指纹；验证码按
 * reset / setpwd 分桶一次性消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordServiceImpl implements PasswordService {

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final TokenService tokenService;
    private final AuditService auditService;

    /**
     * 找回密码（第一步）：提交邮箱或学号，向该账号绑定的安全凭证邮箱发重置验证码。
     *
     * <p><b>2026-10-21 支持邮箱</b>：注册流程已改为邮箱必填、学号选填，
     * 若这里仍只认学号，<b>注册时跳过学号的用户永远找不回密码</b> ——
     * 他连一个可以填进去的值都没有。同时也不再要求记得住 13~14 位学号。
     *
     * <p><b>枚举防护</b>：账号不存在时静默返回成功，不泄露「该账号是否已注册」；
     * 账号存在但未绑邮箱才明确回 {@code EMAIL_NOT_BOUND}。邮箱验证码走 {@code reset} 用途分桶。
     *
     * <p><b>未绑邮箱只可能是历史账号</b>：新注册流程里邮箱是必填项、
     * 且要通过 6 位验证码验证所有权，所以不存在「注册了但没邮箱」的新账号。
     * 这个分支实际只服务于账号体系重构（2026-10-17 之前）留下的老数据。
     */
    @Override
    public void forgotPassword(ForgotPasswordDTO dto) {
        User user = findByAccount(dto.getAccount());
        if (user == null) {
            // 不泄露账号是否存在,静默成功(防枚举)
            return;
        }
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            /*
             * 文案不再说「请先去绑定邮箱」—— 那需要先登录，
             * 而用户此刻正在找回密码页、恰恰是登不进去的状态，指了条走不通的路。
             * 这类账号只能由管理员协助处理。
             */
            throw new BusinessException(EMAIL_NOT_BOUND,
                    "该账号是早期注册的，尚未绑定邮箱，暂时无法自助找回密码，请联系管理员协助处理");
        }
        // 向绑定的安全凭证邮箱发重置验证码(type=reset)
        verifyCodeService.send(user.getEmail(), "reset");
        auditService.record(user.getId(), "forgot_password", "user", user.getId(), "申请找回密码", null);
        log.info("forgotPassword: uid={}, email={}", user.getId(), UserVoAssembler.maskUsername(user.getEmail()));
    }

    /**
     * 按「邮箱或学号」定位账号（找回密码专用）。
     *
     * <p>判据是<b>含不含 @</b>而不是先试邮箱再试学号：
     * 学号是纯数字（正则 {@code ^\d{13,14}$}），邮箱必然含 @，两者不可能混淆，
     * 用 @ 一次分流即可，不必查两次库。
     *
     * <p>用 {@code selectByEmail} / {@code selectByStudentNo} 这两个既有方法，
     * 而不是新写一条把两列 OR 起来的 SQL —— 它们各自带了 {@code is_deleted = 0}
     * 条件，复用它就不会漏掉「注销账号还能找回密码」这种口子。
     */
    private User findByAccount(String account) {
        if (account == null) {
            return null;
        }
        String id = account.trim();
        if (id.isEmpty()) {
            return null;
        }
        if (id.contains("@")) {
            return userMapper.selectByEmail(id.toLowerCase());
        }
        return userMapper.selectByStudentNo(id);
    }

    /**
     * 找回密码(第二步):学号 + 邮箱收到的重置验证码 + 新密码。
     *
     * <p>校验通过后更新密码,并吊销该账号其他设备的 refresh(踢下线),
     * 防止旧 token 在密码被改后仍能继续使用。验证码走 {@code reset} 桶,一次性消费。
     */
    @Override
    public void resetPassword(ResetPasswordDTO dto) {
        User user = findByAccount(dto.getAccount());
        /*
         * 【2026-10-21 修：不再用 EMAIL_NOT_BOUND 区分「账号不存在」】
         *
         * 原来的写法是「user == null 或 未绑邮箱 → 回 1060 EMAIL_NOT_BOUND」，
         * 注释说这是「不泄露账号状态」，实际恰恰泄露了：
         * 攻击者拿一个邮箱试一次，回 1060 就说明**这个邮箱没注册过**
         * （或该账号没绑邮箱），回 1020 才说明存在 —— 一个可枚举的信号。
         *
         * 现在统一回 CODE_INVALID：账号不存在与验证码不对对用户是同一件事
         * ——「你手上这个码用不了」。这也符合用户视角：他确实无法重置。
         *
         * 为什么不像 forgotPassword 那样静默成功：这一步用户已经填了新密码，
         * 静默成功会让他以为改好了、接着去登录却登不进 —— 比明确报错更糟。
         */
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new BusinessException(CODE_INVALID, "验证码错误或已过期");
        }
        // 校验重置验证码(邮箱 + reset 桶,一次性消费)
        verifyCodeService.verify(user.getEmail(), dto.getCode(), "reset");
        user.setPasswordHash(JwtUtil.hashPassword(dto.getNewPassword()));
        userMapper.updateById(user);
        // 吊销其他设备:删 Redis 指纹,旧 refresh 刷新时 CAS 失败全吊销
        tokenService.revoke(user.getId());
        auditService.record(user.getId(), "reset_password", "user", user.getId(), "重置密码", null);
        log.info("resetPassword: uid={}", user.getId());
    }

    /**
     * 修改密码(需登录):校验原密码通过后更新为新密码。
     *
     * <p>原密码不对回 400(不区分「账号不存在」,因为这里是登录态,userId 一定有效)。
     * 新密码与原密码相同也拒绝——改密动作没有发生,却会让用户误以为旧密码已失效。
     *
     * <p>改密后<b>吊销全部 refresh token</b>(2026-10-23 改,审查 P1-26)。
     * 旧实现刻意不删,理由是「本人登录态下主动操作,当前设备保持登录」;但用户改密码的
     * 典型动机是「怀疑账号被盗」——此时攻击者手里的 refresh 仍能续命 3~7 天,改密码
     * 等于没做。安全语义优先:统一吊销,当前设备重新登录即可(access token 15 分钟内
     * 仍有效,不会立刻被踢出正在写的页面)。
     */
    @Override
    @Transactional
    public void changePassword(long userId, ChangePasswordDTO dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED, "未登录");
        }
        if (!JwtUtil.isPasswordSet(user.getPasswordHash())) {
            // 自动建号还没有密码,「原密码」这一步不存在;前端应走设置密码弹层
            throw new BusinessException(PASSWORD_NOT_SET);
        }
        if (!JwtUtil.verifyPassword(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(BAD_REQUEST, "原密码不正确");
        }
        if (JwtUtil.verifyPassword(dto.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(BAD_REQUEST, "新密码不能与原密码相同");
        }
        user.setPasswordHash(JwtUtil.hashPassword(dto.getNewPassword()));
        userMapper.updateById(user);
        // 改密即吊销全部 refresh(审查 P1-26):攻击者持有的旧 refresh 立即失效
        tokenService.revoke(userId);
        auditService.record(userId, "change_password", "user", userId, "修改密码(已吊销全部 refresh token)", null);
        log.info("changePassword: uid={}, refresh revoked", userId);
    }

    /**
     * 给「尚未设置密码」的账号发设置密码验证码。
     *
     * <p>已设密码的账号必须走原密码修改,不能拿邮箱验证码绕开原密码——
     * 邮箱只是找回通道,不是日常改密的第二把钥匙。
     */
    @Override
    public String sendSetPasswordCode(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED, "未登录");
        }
        if (JwtUtil.isPasswordSet(user.getPasswordHash())) {
            throw new BusinessException(PASSWORD_ALREADY_SET);
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BusinessException(EMAIL_NOT_BOUND, "该账号还没有绑定邮箱,无法设置密码");
        }
        return verifyCodeService.send(user.getEmail(), "setpwd");
    }

    /**
     * 无密码账号首次设置密码:邮箱验证码 + 新密码。
     *
     * <p>与 {@link #changePassword} 的边界:本方法只接受
     * {@code password_hash} 仍是哨兵值的账号;一旦设过,后续只能凭原密码修改。
     */
    @Override
    @Transactional
    public void setPassword(long userId, SetPasswordDTO dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED, "未登录");
        }
        if (JwtUtil.isPasswordSet(user.getPasswordHash())) {
            throw new BusinessException(PASSWORD_ALREADY_SET);
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BusinessException(EMAIL_NOT_BOUND, "该账号还没有绑定邮箱,无法设置密码");
        }
        verifyCodeService.verify(user.getEmail(), dto.getCode(), "setpwd");
        user.setPasswordHash(JwtUtil.hashPassword(dto.getNewPassword()));
        userMapper.updateById(user);
        // 与改密同口径:设密后吊销其他设备的 refresh,避免旧会话长期续命
        tokenService.revoke(userId);
        auditService.record(userId, "set_password", "user", userId,
                "邮箱验证码首次设置密码", null);
        log.info("setPassword: uid={}, refresh revoked", userId);
    }
}
