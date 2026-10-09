package com.campus.social.service.impl;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
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

    /** refresh:{userId} = jti */
    private static final String REFRESH_KEY = "refresh:";

    /** 与 JwtConfig 的 access-ttl-millis(900000ms=15min)保持一致 */
    private static final long ACCESS_TTL_SECONDS = 15 * 60;

    /** 与 JwtConfig 的 refresh-ttl-millis(7d)保持一致 */
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    /**
     * 原子 CAS:读 refresh:{uid} 的旧 jti,与新 jti 比对。
     * - 匹配:写新 jti + 刷新 TTL,返回 1(滚动成功)
     * - 不匹配(重用攻击):删 key,返回 0(全吊销)
     * - 无记录:返回 -1(需重新登录)
     * 用 Lua 保证读-判-写在一个原子步骤内,杜绝并发同 jti 双花绕过 rotation。
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

    /** 校验旧 jti,匹配则滚动写入 newJti,返回 true;重用以吊销,返回 false */
    private boolean rotateRefreshJti(long userId, String oldJti, String newJti) {
        Long result = redis.execute(
                REFRESH_CAS_SCRIPT,
                List.of(REFRESH_KEY + userId),
                oldJti, newJti, String.valueOf(REFRESH_TTL.toSeconds()));
        return result != null && result == 1L;
    }

    @Override
    @Transactional
    public TokenPairVO register(RegisterDTO dto) {
        // 1. 先校验验证码(防暴力试密码/防未验证就注册)
        verifyCodeService.verify(dto.getUsername(), dto.getVerifyCode(), "register");

        // 用户名已存在?
        User existing = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername()));
        if (existing != null) {
            throw new BusinessException(USERNAME_EXISTS, "该邮箱/手机号已注册");
        }
        // 学号已存在?
        if (dto.getStudentNo() != null && !dto.getStudentNo().isBlank()) {
            User bySno = userMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                            .eq(User::getStudentNo, dto.getStudentNo()));
            if (bySno != null) {
                throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号绑定");
            }
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(JwtUtil.hashPassword(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setStudentNo(dto.getStudentNo());
        user.setStatus(0); // 正常
        userMapper.insert(user);

        // 审计:注册
        auditService.record(user.getId(), "register", "user", user.getId(),
                "username=" + dto.getUsername(), null);

        log.info("register: uid={}, username={}", user.getId(), dto.getUsername());
        return issueTokens(user);
    }

    @Override
    public TokenPairVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername()));
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
        // 审计:登录
        auditService.record(user.getId(), "login", "user", user.getId(),
                "username=" + user.getUsername(), null);
        return issueTokens(user);
    }

    @Override
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        // 校验邮箱验证码(登录用途,一次性消费)
        verifyCodeService.verify(dto.getEmail(), dto.getCode(), "login");

        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getEmail()));
        if (user == null) {
            throw new BusinessException(EMAIL_NOT_REGISTERED, "该邮箱尚未注册");
        }
        if (user.getStatus() == 1) {
            throw new BusinessException(USER_FROZEN);
        }
        if (user.getStatus() == 2) {
            throw new BusinessException(USER_CANCELLED);
        }
        log.info("loginByCode: email={}", dto.getEmail());
        return issueTokens(user);
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
        // 签新 refresh(新 jti),先不写 Redis
        String newJti = UUID.randomUUID().toString().replace("-", "");
        String newRefresh = JwtUtil.issueRefresh(userId, newJti);

        // 原子 CAS:旧 jti 匹配才滚动写入新 jti(杜绝并发同 jti 双花绕过 rotation)
        if (!rotateRefreshJti(userId, oldJti, newJti)) {
            // 重用(-1 无记录 / 0 不匹配,已被 DEL)→ 全吊销
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
        if (studentNo != null && !studentNo.isBlank()
                && !studentNo.equals(user.getStudentNo())) {
            studentNoChangeSupport.apply(user, studentNo, oldStudentNo);
        }
        if (nickname != null)
            user.setNickname(nickname);
        if (avatarUrl != null)
            user.setAvatarUrl(avatarUrl);
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

    // ----------------------------------------------------------------

    /** null 安全展示:值 null 时显示「空」,避免 detail 里出现 "null" */
    private String nullSafe(String v) {
        return v == null ? "空" : v;
    }

    /** 签发 access + refresh,refresh jti 写入 Redis(注册/登录首次签发,无旧 jti 需比对) */
    private TokenPairVO issueTokens(User user) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        String access = JwtUtil.issueAccess(user.getId(), user.getUsername());
        String refresh = JwtUtil.issueRefresh(user.getId(), jti);
        redis.opsForValue().set(REFRESH_KEY + user.getId(), jti, REFRESH_TTL);

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
