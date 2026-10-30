package com.campus.social.controller;

import com.campus.social.common.Result;
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
 * 校园信息（F2）。
 *
 * <p>2026-10-30 起定位为<b>可选资料</b>：填写与否不影响发帖、搭话、建圈等
 * 任何功能；学校不提供学生名单，这里也不对外宣称「已认证身份」。
 *
 * <p>挂在 {@code /user} 下而非独立 {@code /verify} 前缀：
 * 这三个动作都是「改我的资料」，与 {@code /user/me} 同属一个资源，
 * 拆到另一个前缀只会让前端多记一条路由。
 *
 * <p>2026-10-30 删除校园邮箱验证码路径：学生没有学校发放的校园邮箱，
 * 现有邮箱是 QQ / 网易等个人邮箱，按域名白名单放行只会误伤。
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
     * 提交学号：格式与归属一致则直接登记，否则进核对队列（verify_status=2）
     */
    @PostMapping("/verify/student-no")
    public Result<StudentNoVerifyVO> submitStudentNo(@Valid @RequestBody StudentNoVerifyDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.submitStudentNo(me, dto));
    }

    /** 当前校园信息状态。只用于资料页展示，不承载任何功能权限 */
    @GetMapping("/verify/status")
    public Result<VerifyStatusVO> status(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(campusVerifyService.status(me));
    }
}