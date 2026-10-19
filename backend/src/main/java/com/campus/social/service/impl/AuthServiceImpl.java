package com.campus.social.service.impl;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.BindEmailDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.service.UploadService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
    private final StringRedisTemplate redis;
    private final VerifyCodeService verifyCodeService;
    private final com.campus.social.service.AuditService auditService;
    /** 改学号的唯一校验与核验状态迁移，见该类的javadoc（本轮修的死循环） */
    private final com.campus.social.service.support.StudentNoChangeSupport studentNoChangeSupport;
    /** 头像归属校验:设置头像时,若引用的是本服务 /uploads 文件则必须确属该用户上传 */
    private final UploadService uploadService;
    /** 注册即认证:带学号 + 归属注册时,复用存归属与六层校验逻辑,避免两套实现 */
    private final com.campus.social.service.CampusVerifyService campusVerifyService;

    /** refresh:{userId} = jti */
    private static final String REFRESH_KEY = "refresh:";

    /** 与 JwtConfig 的 access-ttl-millis(900000ms=15min)保持一致 */
    private static final long ACCESS_TTL_SECONDS = 15 * 60;

    /**
     * refresh 默认有效期(不勾记住我)= 3d,与 JwtConfig 的 refresh-ttl-millis 对齐。
     * 勾选记住我 = 7d,见 {@link #refreshTtl(boolean)}。
     *
     * <p>Redis key 的过期时间必须与 JwtUtil 签发的 token 过期时间完全一致,
     * 否则会出现「token 还有效但 Redis 指纹已过期」或反过来的错位。
     * 这里直接读 JwtUtil 暴露的毫秒值做单一来源,杜绝两处各写死导致漂移。
     */
    private static Duration refreshTtl(boolean remember) {
        long ms = remember ? JwtUtil.getRememberTtlMillis() : JwtUtil.getRefreshTtlMillis();
        return Duration.ofMillis(ms);
    }

    /**
     * 原子 CAS:读 refresh:{uid} 的旧 jti,与新 jti 比对。
     * - 匹配:写新 jti + 刷新 TTL,返回 1(滚动成功)
     * - 不匹配(重用攻击):删 key,返回 0(全吊销)
     * - 无记录:返回 -1(需重新登录)
     * 用 Lua 保证读-判-写在一个原子步骤内,杜绝并发同 jti 双花绕过 rotation。
     *
     * @param ttlSeconds 写入新 jti 的过期秒数,必须与签发 token 的 TTL 一致
     *                   (勾选记住我=7d,否则 3d),否则 Redis 指纹与 token 会错位。
     */
    private static final RedisScript<Long> REFRESH_CAS_SCRIPT;

    static {
        String lua =
                "local cur = redis.call('GET', KEYS[1])\n" +
                "if cur == false then return -1 end\n" +
                "if cur ~= ARGV[1] then\n" +
                "  redis.call('DEL', KEYS[1])\n" +
                "  return 0\n" +
                "end\n" +
                "redis.call('SET', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3]))\n" +
                "return 1";
        REFRESH_CAS_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * 校验旧 jti,匹配则滚动写入 newJti。
     *
     * @return 1=滚动成功;0=旧 jti 不匹配(重用攻击,已全吊销);-1=无记录(key 自然过期或从未写过)
     */
    private int rotateRefreshJti(long userId, String oldJti, String newJti, long ttlSeconds) {
        Long result = redis.execute(
                REFRESH_CAS_SCRIPT,
                List.of(REFRESH_KEY + userId),
                oldJti, newJti, String.valueOf(ttlSeconds));
        if (result == null) {
            return -1;
        }
        return result.intValue();
    }

    @Override
    @Transactional
    public TokenPairVO register(RegisterDTO dto) {
        // 账号体系重构 2026-10-17:学号为主账号,注册不再发/校验验证码。
        // 安全凭证邮箱选填,注册时不校验所有权(避免引入验证码链路),有值才写库。

        // 学号(主账号)已存在?username 与 student_no 两列都可能持有学号,都查。
        User existing = userMapper.selectByStudentNo(dto.getStudentNo());
        if (existing != null) {
            throw new BusinessException(USERNAME_EXISTS, "该学号已注册");
        }
        // 安全凭证邮箱已存在?(有值时 UNIQUE,防两账号绑同一邮箱)
        String email = dto.getEmail() == null ? null : dto.getEmail().trim().toLowerCase();
        if (email != null && !email.isEmpty()) {
            User byEmail = userMapper.selectByEmail(email);
            if (byEmail != null) {
                throw new BusinessException(EMAIL_ALREADY_BOUND, "该邮箱已被其他账号绑定");
            }
        }

        User user = new User();
        // 主账号:username 内部统一存学号(与 student_no 同值)
        user.setUsername(dto.getStudentNo());
        user.setPasswordHash(JwtUtil.hashPassword(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setStudentNo(dto.getStudentNo());
        user.setEmail(email);   // 选填,无值则 NULL(UNIQUE 对 NULL 放行)
        user.setStatus(0); // 正常
        userMapper.insert(user);

        // 注册即认证:带了合法学号 + 学校归属,则一次性存归属并跑六层校验。
        // 六层不过 / 学号被占用 → 抛异常,整体事务回滚,用户不落库(干净失败,
        // 不会留下「学号与归属不符」的脏数据)。学号为空则只注册、不认证、不存归属。
        if (dto.getStudentNo() != null && !dto.getStudentNo().isBlank()) {
            if (dto.getDeptId() == null || dto.getGradeId() == null || dto.getMajorId() == null) {
                throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE,
                        "请完善学校归属（学院 / 年级 / 专业）后再提交学号");
            }
            SchoolProfileDTO profileDto = new SchoolProfileDTO();
            // schoolId 不传时由 saveSchoolProfile 兜底成本校;MVP 单校恒为 1
            profileDto.setSchoolId(dto.getSchoolId());
            profileDto.setDeptId(dto.getDeptId());
            profileDto.setGradeId(dto.getGradeId());
            profileDto.setMajorId(dto.getMajorId());
            campusVerifyService.saveSchoolProfile(user.getId(), profileDto);

            StudentNoVerifyDTO verifyDto = new StudentNoVerifyDTO();
            verifyDto.setStudentNo(dto.getStudentNo());
            // 注册流程没有「姓名后 1-2 位」字段,传空串;管理员比对以学号 + 归属为主
            verifyDto.setNameTail("");
            campusVerifyService.submitStudentNo(user.getId(), verifyDto);
        }

        // 审计:注册
        auditService.record(user.getId(), "register", "user", user.getId(),
                "username=" + dto.getStudentNo(), null);

        log.info("register: uid={}, studentNo={}", user.getId(), dto.getStudentNo());
        // 注册默认不勾记住我(记住我是密码登录的优化项)
        return issueTokens(user, false);
    }

    @Override
    public TokenPairVO login(LoginDTO dto) {
        // 账号体系重构 2026-10-17:登录标识走 username(学号)OR email 双匹配。
        // 旧账号 username 仍是旧主标识(邮箱/手机),新账号 username=学号,
        // 已绑定邮箱的用户也能用邮箱登录。统一用 WRONG_PASSWORD,防账号枚举。
        String id = dto.getUsername() == null ? "" : dto.getUsername().trim().toLowerCase();
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, id)
                        .or()
                        .eq(User::getEmail, id));
        if (user == null) {
            throw new BusinessException(WRONG_PASSWORD);
        }
        // 冻结/注销账号拒登录
        if (user.getStatus() == 1) {
            throw new BusinessException(USER_FROZEN);
        }
        if (user.getStatus() == 2) {
            throw new BusinessException(USER_CANCELLED);
        }
        if (!JwtUtil.verifyPassword(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(WRONG_PASSWORD);
        }
        // 记住我:勾选 → refresh 7d,不勾 → 3d(见 refreshTtl)
        boolean remember = Boolean.TRUE.equals(dto.getRememberMe());
        // 审计:登录
        auditService.record(user.getId(), "login", "user", user.getId(),
                "username=" + user.getUsername(), null);
        return issueTokens(user, remember);
    }

    @Override
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        // 校验邮箱验证码(登录用途,一次性消费)
        verifyCodeService.verify(dto.getEmail(), dto.getCode(), "login");

        // 账号体系重构:邮箱验证码登录只服务于「已绑定安全凭证邮箱」的用户,
        // 用 email 列查询(而非原 username —— 新账号 username 已是学号)。
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, dto.getEmail().trim().toLowerCase()));
        if (user == null) {
            throw new BusinessException(EMAIL_NOT_REGISTERED, "该邮箱尚未绑定账号");
        }
        if (user.getStatus() == 1) {
            throw new BusinessException(USER_FROZEN);
        }
        if (user.getStatus() == 2) {
            throw new BusinessException(USER_CANCELLED);
        }
        log.info("loginByCode: email={}", dto.getEmail());
        // 邮箱验证码登录属「免密码」,不勾记住我(默认 3d)
        return issueTokens(user, false);
    }

    @Override
    public String sendCode(SendCodeDTO dto) {
        return verifyCodeService.send(dto.getTarget(), dto.getType() == null ? "register" : dto.getType());
    }

    @Override
    public TokenPairVO refresh(RefreshDTO dto) {
        Claims claims = JwtUtil.parseRefresh(dto.getRefreshToken());
        if (claims == null) {
            throw new BusinessException(REFRESH_TOKEN_INVALID);
        }
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BusinessException(REFRESH_TOKEN_INVALID);
        }
        String oldJti = claims.getId();
        // 记住我保活:按旧 token 的 remember 选同 TTL 重签,否则「记住我」会在
        // 首次静默刷新后悄悄失效(用户明明勾了 7 天,刷一次又变 3 天)。
        boolean remember = JwtUtil.parseRemember(claims);
        // 签新 refresh(新 jti),先不写 Redis
        String newJti = UUID.randomUUID().toString().replace("-", "");
        String newRefresh = JwtUtil.issueRefresh(userId, newJti, remember);

        // 原子 CAS:旧 jti 匹配才滚动写入新 jti(杜绝并发同 jti 双花绕过 rotation)
        // TTL 必须与签发 token 的 TTL 一致(勾选记住我=7d,否则 3d)
        int cas = rotateRefreshJti(userId, oldJti, newJti, refreshTtl(remember).getSeconds());
        if (cas != 1) {
            // -1:无记录(refresh token 自然过期/已登出)→ 正常失效,重新登录即可
            //  0:旧 jti 不匹配(重用攻击,已被 DEL 全吊销)→ 安全事件
            if (cas == -1) {
                throw new BusinessException(REFRESH_TOKEN_INVALID, "refresh token 已失效,请重新登录");
            }
            throw new BusinessException(REFRESH_TOKEN_REUSED, "refresh token 已被吊销,请重新登录");
        }

        // CAS 通过后校验账号状态
        User user = userMapper.selectById(userId);
        if (user == null) {
            redis.delete(REFRESH_KEY + userId);
            throw new BusinessException(REFRESH_TOKEN_INVALID, "用户不存在");
        }
        if (user.getStatus() == 1) {
            redis.delete(REFRESH_KEY + userId);
            throw new BusinessException(USER_FROZEN);
        }
        String newAccess = JwtUtil.issueAccess(userId, user.getUsername());
        long accessExpireAt = System.currentTimeMillis() / 1000 + ACCESS_TTL_SECONDS;
        return TokenPairVO.builder()
                .accessToken(newAccess)
                .refreshToken(newRefresh)
                .accessExpireAt(accessExpireAt)
                .build();
    }

    @Override
    public void logout(long userId) {
        redis.delete(REFRESH_KEY + userId);
        log.info("logout: uid={}", userId);
        // 审计:登出
        auditService.record(userId, "logout", "user", userId, "退出登录", null);
    }

    // ----------------------------------------------------------------
    // 安全凭证邮箱:找回密码 / 绑定(账号体系重构 2026-10-17)

    /**
     * 找回密码(第一步):提交学号,向该账号绑定的安全凭证邮箱发重置验证码。
     *
     * <p><b>枚举防护</b>:学号不存在时静默返回成功,不泄露「该学号是否已注册」;
     * 账号存在但未绑邮箱才明确回 {@code EMAIL_NOT_BOUND}(用户输的是自己的学号,
     * 提示去绑定是合理 UX,不算泄露)。邮箱验证码走 {@code reset} 用途分桶。
     */
    @Override
    public void forgotPassword(ForgotPasswordDTO dto) {
        User user = userMapper.selectByStudentNo(dto.getStudentNo().trim());
        if (user == null) {
            // 不泄露账号是否存在,静默成功(防学号枚举)
            return;
        }
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new BusinessException(EMAIL_NOT_BOUND, "该学号尚未绑定邮箱,无法使用邮箱验证码找回密码");
        }
        // 向绑定的安全凭证邮箱发重置验证码(type=reset)
        verifyCodeService.send(user.getEmail(), "reset");
        auditService.record(user.getId(), "forgot_password", "user", user.getId(), "申请找回密码", null);
        log.info("forgotPassword: uid={}, email={}", user.getId(), user.getEmail());
    }

    /**
     * 找回密码(第二步):学号 + 邮箱收到的重置验证码 + 新密码。
     *
     * <p>校验通过后更新密码,并吊销该账号其他设备的 refresh(踢下线),
     * 防止旧 token 在密码被改后仍能继续使用。验证码走 {@code reset} 桶,一次性消费。
     */
    @Override
    public void resetPassword(ResetPasswordDTO dto) {
        User user = userMapper.selectByStudentNo(dto.getStudentNo().trim());
        if (user == null || user.getEmail() == null || user.getEmail().isEmpty()) {
            // 账号不存在 / 未绑邮箱:统一回 EMAIL_NOT_BOUND,不泄露账号状态
            throw new BusinessException(EMAIL_NOT_BOUND, "该学号尚未绑定邮箱,无法找回");
        }
        // 校验重置验证码(邮箱 + reset 桶,一次性消费)
        verifyCodeService.verify(user.getEmail(), dto.getCode(), "reset");
        user.setPasswordHash(JwtUtil.hashPassword(dto.getNewPassword()));
        userMapper.updateById(user);
        // 吊销其他设备:删 Redis 指纹,旧 refresh 刷新时 CAS 失败全吊销
        redis.delete(REFRESH_KEY + user.getId());
        auditService.record(user.getId(), "reset_password", "user", user.getId(), "重置密码", null);
        log.info("resetPassword: uid={}", user.getId());
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
        log.info("bindEmail: uid={}, email={}", userId, email);
        return toVo(user);
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
    public UserVO updateProfile(long userId, String nickname, String avatarUrl, String studentNo) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED);
        }
        // 先捕获旧值,updateById 之后 user 字段就变新值了,审计 diff 需要旧值做"→"左边
        String oldNickname = user.getNickname();
        String oldStudentNo = user.getStudentNo();
        String oldAvatar = user.getAvatarUrl();
        // 学号变更需唯一校验；且被驳回的用户换了学号要重新进待审队列。
        // 判定细节见 StudentNoChangeSupport（那边写清了为什么这么判）。
        //
        // 第二个分支是补的洞:学号**没变**但 verify_status=0(从未提交过核验)
        // 的用户,只判「与旧值不同」会永远走不到 apply —— 他填过一次学号、
        // 学号已落库,再点保存判定「没变化」,状态永远 0、主页永远「去认证」
        // (WJ 实测:学号存上了,状态纹丝不动)。已认证(1)/审核中(2)不在此列,
        // 不会被重新拉回队列。
        if (studentNo != null && !studentNo.isBlank()
                && (!studentNo.equals(user.getStudentNo())
                    || Integer.valueOf(0).equals(user.getVerifyStatus()))) {
            studentNoChangeSupport.apply(user, studentNo, oldStudentNo);
        }
        if (nickname != null)
            user.setNickname(nickname);
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

    /** 签发 access + refresh,refresh jti 写入 Redis(注册/登录首次签发,无旧 jti 需比对) */
    private TokenPairVO issueTokens(User user, boolean remember) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        String access = JwtUtil.issueAccess(user.getId(), user.getUsername());
        String refresh = JwtUtil.issueRefresh(user.getId(), jti, remember);
        // Redis 指纹 TTL 必须与 token 过期时间一致(勾选记住我=7d,否则 3d)
        redis.opsForValue().set(REFRESH_KEY + user.getId(), jti, refreshTtl(remember));

        long accessExpireAt = System.currentTimeMillis() / 1000 + ACCESS_TTL_SECONDS;
        return TokenPairVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .accessExpireAt(accessExpireAt)
                .build();
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
