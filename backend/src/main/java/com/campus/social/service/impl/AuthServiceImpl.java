package com.campus.social.service.impl;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.BindEmailDTO;
import com.campus.social.dto.SetPasswordDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.service.LoginService;
import com.campus.social.service.RefreshTokenService;
import com.campus.social.service.RegisterService;
import com.campus.social.service.UploadService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.TokenService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.*;

/**
 * 账号鉴权实现
 *
 * refresh token 吊销设计:
 * - 每次签 refresh 时,Redis key=refresh:{userId} 存 jti 指纹,TTL = refreshTtl
 * - /auth/refresh:比对 jti,不一致 → 防重用攻击(REFRESH_TOKEN_REUSED)
 * - 刷新成功后立刻删除旧 key,写新 key(滚动)
 * - /auth/logout:删 key,refresh 立刻失效
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final com.campus.social.service.AuditService auditService;
    /** 改学号的唯一校验与核验状态迁移，见该类的javadoc（本轮修的死循环） */
    private final com.campus.social.service.support.StudentNoChangeSupport studentNoChangeSupport;
    /** 头像归属校验:设置头像时,若引用的是本服务 /uploads 文件则必须确属该用户上传 */
    private final UploadService uploadService;
    /** 注册与验证码自动建号 */
    private final RegisterService registerService;
    /** 密码登录与验证码登录 */
    private final LoginService loginService;
    /** refresh 轮换与登出吊销 */
    private final RefreshTokenService refreshTokenService;
    /** Token 签发、Redis 指纹轮换与吊销，机制细节见该类的 javadoc */
    private final TokenService tokenService;

    @Override
    @Transactional
    public TokenPairVO register(RegisterDTO dto) {
        return registerService.register(dto);
    }

    @Override
    public TokenPairVO login(LoginDTO dto, String ip) {
        return loginService.login(dto, ip);
    }

    @Override
    @Transactional
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        return loginService.loginByCode(dto);
    }

    @Override
    public String sendCode(SendCodeDTO dto) {
        return verifyCodeService.send(dto.getTarget(), dto.getType() == null ? "register" : dto.getType());
    }

    @Override
    public TokenPairVO refresh(RefreshDTO dto) {
        return refreshTokenService.refresh(dto);
    }

    @Override
    public void logout(long userId) {
        refreshTokenService.logout(userId);
    }

    // ----------------------------------------------------------------
    // 安全凭证邮箱:找回密码 / 绑定(账号体系重构 2026-10-17)

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
     * 绑定 / 修改安全凭证邮箱(需登录)。
     *
     * <p>流程:先 {@code POST /auth/send-code} type=bind 把码发到目标邮箱,
     * 再带码调用本端点。校验通过后写 {@code user.email}(转小写),全局唯一
     * (uk_user_email)——与其他账号冲突回 {@code EMAIL_ALREADY_BOUND}。
     */
    @Override
    @Transactional
    public UserVO bindEmail(Long userId, BindEmailDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        // 先校验绑定验证码(发往目标邮箱,type=bind,一次性消费)
        verifyCodeService.verify(email, dto.getCode(), "bind");
        // 唯一性:其他账号已绑此邮箱 → 拒绝(自己换绑自己的邮箱不算冲突)
        User existing = userMapper.selectByEmail(email);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException(EMAIL_ALREADY_BOUND, "该邮箱已被其他账号绑定");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED, "用户不存在");
        }
        user.setEmail(email);
        userMapper.updateById(user);
        auditService.record(userId, "bind_email", "user", userId, "绑定邮箱=" + email, null);
        log.info("bindEmail: uid={}, email={}", userId, UserVoAssembler.maskUsername(email));
        return toVo(user);
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

    @Override
    public UserVO me(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        return toVo(user);
    }

    /**
     * 更新资料：昵称 / 头像 / 学号。
     *
     * <p><b>院系与年级不在此处改</b>：它们已改为字典 id（见 {@code UpdateProfileDTO} 注释），
     * 走 {@code POST /user/school-profile} 或 {@code POST /onboarding/complete}。
     * 保留自由文本入口会让展示名称与 dept_id 失配，污染 AC-14 的匹配依据。
     *
     * <p><b>改学号会连带推进核验状态</b>：这是本轮修的死循环。
     * 原先这里只改 {@code student_no} 不碰 {@code verify_status}，
     * 于是被驳回（3）的用户即使改了学号，状态仍停在 3，
     * 新学号永远进不了待审队列，而管理员一个人在校园里不会知道有人在等他。
     * 前端提示条的「去改学号」按钮点了等于没点。
     */
    @Override
    @Transactional
    public UserVO updateProfile(long userId, String nickname, String avatarUrl, String studentNo, String bio) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        // 先捕获旧值,updateById 之后 user 字段就变新值了,审计 diff 需要旧值做"→"左边
        String oldNickname = user.getNickname();
        String oldStudentNo = user.getStudentNo();
        String oldAvatar = user.getAvatarUrl();
        String oldBio = user.getBio();
        // 学号变更需唯一校验；且被驳回的用户换了学号要重新进待审队列。
        // 判定细节见 StudentNoChangeSupport（那边写清了为什么这么判）。
        //
        // 第二个分支是补的洞:学号**没变**但 verify_status=0(从未提交过核验)
        // 的用户,只判「与旧值不同」会永远走不到 apply —— 他填过一次学号、
        // 学号已落库,再点保存判定「没变化」,状态永远 0、主页永远「去认证」
        // (WJ 实测:学号存上了,状态纹丝不动)。
        //
        // 2026-10-28 追加状态 3：驳回时 student_no 已被释放为 NULL，正常路径
        // 一定会「不同」；但老数据里被驳回的账号仍留着旧学号，同号保存
        // 也必须能重新提交。已认证(1)/审核中(2)不在此列，不会被重新拉回队列。
        if (studentNo != null && !studentNo.isBlank()
                && (!studentNo.equals(user.getStudentNo())
                    || Integer.valueOf(0).equals(user.getVerifyStatus())
                    || Integer.valueOf(3).equals(user.getVerifyStatus()))) {
            studentNoChangeSupport.apply(user, studentNo);
        }
        if (nickname != null)
            user.setNickname(nickname);
        /**
         * 个人介绍（2026-10-19）。与 nickname 一样「null = 本次不改」，
         * 所以清空介绍要传空串而不是不传 —— 前端清空时确实会传 ''。
         * trim 掉纯空白：用户按空格提交不该在主页占一行空白。
         */
        if (bio != null) {
            user.setBio(bio.trim().isEmpty() ? null : bio.trim());
        }
        if (avatarUrl != null) {
            // 归属校验:引用 /uploads 文件必须是本人上传的;老数据/外部图床放行
            if (!uploadService.isAvatarAllowed(avatarUrl, userId)) {
                throw new BusinessException(BAD_REQUEST, "头像必须是您自己上传的图片");
            }
            user.setAvatarUrl(avatarUrl);
        }
        userMapper.updateById(user);
        // 审计:改资料,记 "字段: 旧值 → 新值" 的 diff(只记有实际变化的字段,避免噪声)
        StringBuilder diff = new StringBuilder();
        if (nickname != null && !nickname.equals(oldNickname))
            diff.append("昵称:").append(nullSafe(oldNickname)).append("→").append(nickname).append("; ");
        // 介绍只记「改了/没改」不记原文：200 字进审计日志没有排查价值，
        // 而审计表会被检索，凭一条「简介已修改」足以定位时间点。
        // 比较用 trim 后的值，与上面落库时的归一化保持同一口径，
        // 否则「从空改成只有空格」会被记成一次修改，而库里其实什么都没变。
        if (bio != null && !bio.trim().equals(oldBio == null ? "" : oldBio.trim()))
            diff.append("个人介绍:已修改; ");
        if (studentNo != null && !studentNo.isBlank() && !studentNo.equals(oldStudentNo))
            diff.append("学号:").append(nullSafe(oldStudentNo)).append("→").append(studentNo).append("; ");
        if (avatarUrl != null && !avatarUrl.equals(oldAvatar))
            diff.append("头像:已更换; ");
        if (diff.length() > 0) {
            auditService.record(userId, "update_profile", "user", userId, "改资料: " + diff.toString().trim(), null);
        }
        return toVo(user);
    }

    /**
     * 设置 / 清除主页封面（2026-10-20）。
     *
     * <p><b>语义：传什么就是什么。</b>空白 / null 一律落成 NULL，
     * 也就是「恢复默认渐变封面」——这里<b>不能</b>沿用 {@code updateProfile}
     * 那套「null = 本次不改」，否则用户点「恢复默认」传个 null 会静默无效。
     *
     * <p>归属校验复用 {@code isAvatarAllowed}：它判的是「这个 /uploads 路径是不是
     * 你自己传的」，与文件名无关，封面同理。不校验的后果是任何人可以把自己封面
     * 指向别人的图 —— 能用但不能占为己有？不，是能把别人相册里的图挂到自己主页上，
     * 属于越权引用。
     */
    @Override
    @Transactional
    public UserVO updateCover(long userId, String coverUrl) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        String next = (coverUrl == null || coverUrl.isBlank()) ? null : coverUrl.trim();
        String old = user.getCoverUrl();
        if (next == null ? old == null : next.equals(old)) {
            // 没变化就不写库、不记审计：反复点「恢复默认」不该在审计表里刷出一堆空记录
            return toVo(user);
        }
        if (next != null && !uploadService.isAvatarAllowed(next, userId)) {
            throw new BusinessException(BAD_REQUEST, "封面必须是您自己上传的图片");
        }
        // 必须走显式 SQL：updateById 会跳过 null 字段，恢复默认会静默失效
        // （详见 UserMapper#updateCoverUrl 的注释）
        userMapper.updateCoverUrl(userId, next);
        user.setCoverUrl(next);
        // 审计只记动作不记路径：路径是 40 位 uuid，写进日志既占地方又没法读
        auditService.record(userId, "update_cover", "user", userId,
                next == null ? "封面:恢复默认" : "封面:已更换", null);
        return toVo(user);
    }

    /**
     * 设置陌生人搭话开关。只动 {@code stranger_msg_enabled} 一列，
     * 不走 {@link #updateProfile}（那里会连带触发学号核验流转，开关不该捅到那条链）。
     */
    @Override
    @Transactional
    public UserVO setStrangerMsgEnabled(long userId, boolean enabled) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        Integer next = enabled ? 1 : 0;
        if (!next.equals(user.getStrangerMsgEnabled())) {
            user.setStrangerMsgEnabled(next);
            userMapper.updateById(user);
            auditService.record(userId, "set_stranger_msg", "user", userId,
                    "允许陌生人发消息: " + enabled, null);
        }
        return toVo(user);
    }

    // ----------------------------------------------------------------

    /** null 安全展示:值 null 时显示「空」,避免 detail 里出现 "null" */
    private String nullSafe(String v) {
        return v == null ? "空" : v;
    }

    /**
     * 「我的资料」装配：全项目唯一允许带 role 的路径。
     *
     * <p>调用点只有 {@link #me} 与 {@link #updateProfile} 两个——判断标准是
     * 「这个响应里出现的是我吗」。其他任何位置（Feed、评论区、私聊、通知、
     * 他人主页）都必须走 {@code UserVoAssembler.of}。
     */
    private UserVO toVo(User user) {
        return UserVoAssembler.ofPrivate(user);
    }
}
