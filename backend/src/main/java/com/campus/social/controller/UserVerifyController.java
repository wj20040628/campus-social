package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.CampusEmailConfirmDTO;
import com.campus.social.dto.CampusEmailSendDTO;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.CampusVerifyService;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校内认证（F2）。
 *
 * <p>挂在 {@code /user} 下而非独立 {@code /verify} 前缀：
 * 这五个动作都是「改我的资料」，与 {@code /user/me} 同属一个资源，
 * 拆到另一个前缀只会让前端多记一条路由。
 *
 * <p>本类<b>不</b>加类级 {@code @RequireAdmin}——全部是「我自己」的读写。
 */
@RestController
@RequestMapping("/user")
public class UserVerifyController {

    private final CampusVerifyService campusVerifyService;

    public UserVerifyController(CampusVerifyService campusVerifyService) {
        this.campusVerifyService = campusVerifyService;
    }

    /** 设置学校 / 学院 / 年级（引导第 1 步，AC-18） */
    @PostMapping("/school-profile")
    public Result<SchoolProfileVO> schoolProfile(@Valid @RequestBody SchoolProfileDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.saveSchoolProfile(me, dto));
    }

    /**
     * 发起校园邮箱验证码。
     *
     * <p>校验顺序：先判域名白名单，再写 Redis、再调 SMTP
     * （ARCHITECTURE §3 Q9.2）。否则任意邮箱都会成为 Redis key 并被送进真实邮件通道。
     */
    @PostMapping("/verify/campus-email")
    public Result<String> sendCampusEmail(@Valid @RequestBody CampusEmailSendDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.sendCampusEmailCode(me, dto));
    }

    /** 确认校园邮箱验证码，通过后立即认证 */
    @PostMapping("/verify/campus-email/confirm")
    public Result<VerifyStatusVO> confirmCampusEmail(@Valid @RequestBody CampusEmailConfirmDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.confirmCampusEmail(me, dto));
    }

    /** 提交学号认证，进人工核验队列（队列即 user 表 verify_status=2） */
    @PostMapping("/verify/student-no")
    public Result<StudentNoVerifyVO> submitStudentNo(@Valid @RequestBody StudentNoVerifyDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.submitStudentNo(me, dto));
    }

    /** 当前认证状态。AC-11 的两个能力布尔量由此下发，前端不自行推导 */
    @GetMapping("/verify/status")
    public Result<VerifyStatusVO> status(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.status(me));
    }
}