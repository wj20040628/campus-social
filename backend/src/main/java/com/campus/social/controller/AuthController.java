package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.StudentNoAppealDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.AuthService;
import com.campus.social.service.FeedbackService;
import com.campus.social.vo.FeedbackSubmitVO;
import com.campus.social.vo.TokenPairVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final FeedbackService feedbackService;

    @PostMapping("/register")
    public Result<TokenPairVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(authService.register(dto));
    }

    /**
     * 未注册用户的学号认证申诉（2026-10-19）。
     *
     * <p>学号校验失败的人没有账号、发不出需登录的 POST /feedback（401），
     * 而 feedback.user_id 原本 NOT NULL 也存不进。故开这条公开端点
     * （/auth/** 已整体在白名单），收集学号 + 联系邮箱 + 可选说明，
     * 写进 feedback 队列（user_id 置 NULL），管理员照邮箱联系。
     * 委托 {@link FeedbackService#submitStudentNoAppeal}，不污染 AuthService。
     */
    @PostMapping("/appeal-student-no")
    public Result<FeedbackSubmitVO> appealStudentNo(@Valid @RequestBody StudentNoAppealDTO dto) {
        return Result.ok(feedbackService.submitStudentNoAppeal(dto));
    }

    @PostMapping("/login")
    public Result<TokenPairVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest req) {
        return Result.ok(authService.login(dto, clientIp(req)));
    }

    /** 失败锁定的维度:优先代理头(部署在 Nginx 后),无则回源地址 */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : req.getRemoteAddr();
    }

    /** 邮箱验证码登录(免密码) */
    @PostMapping("/login-code")
    public Result<TokenPairVO> loginByCode(@Valid @RequestBody LoginByCodeDTO dto) {
        return Result.ok(authService.loginByCode(dto));
    }

    /**
     * 发送验证码(register 已不再发码 / login 邮箱验证码登录 / bind 绑定邮箱 / reset 找回密码)
     * 开发模式:打后端控制台;生产模式:走 SMTP / 阿里云 DM 网关
     */
    @PostMapping("/send-code")
    public Result<Map<String, String>> sendCode(@Valid @RequestBody SendCodeDTO dto) {
        String msg = authService.sendCode(dto);
        return Result.ok(Map.of("message", msg));
    }

    /**
     * 找回密码(第一步):提交学号,向绑定的安全凭证邮箱发重置验证码。
     * 账号不存在静默成功(防学号枚举);未绑邮箱回 EMAIL_NOT_BOUND。
     */
    @PostMapping("/forgot-password")
    public Result<Void> forgotPassword(@Valid @RequestBody ForgotPasswordDTO dto) {
        authService.forgotPassword(dto);
        return Result.ok();
    }

    /**
     * 找回密码(第二步):学号 + 邮箱重置验证码 + 新密码。
     * 校验通过后改密并吊销其他设备的 refresh。
     */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        authService.resetPassword(dto);
        return Result.ok();
    }

    @PostMapping("/refresh")
    public Result<TokenPairVO> refresh(@Valid @RequestBody RefreshDTO dto) {
        return Result.ok(authService.refresh(dto));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            // 理论上 /auth/logout 已被拦截器鉴权并塞入 userId;
            // 走到这里是配置回归(如 exclude 误伤),返 401 而非 NPE
            return Result.fail(com.campus.social.common.ResultCode.UNAUTHORIZED, "未登录");
        }
        authService.logout(userId);
        return Result.ok();
    }
}
