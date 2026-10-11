package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.UpdateProfileDTO;
import com.campus.social.entity.User;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    private final AuthService authService;
    private final UserMapper userMapper;

    public UserController(AuthService authService, UserMapper userMapper) {
        this.authService = authService;
        this.userMapper = userMapper;
    }

    @GetMapping("/me")
    public Result<UserVO> me(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(authService.me(userId));
    }

    /** 更新资料:昵称/头像/学号。院系与年级走 POST /user/school-profile(字典 id) */
    @PutMapping("/me")
    public Result<UserVO> update(@Valid @RequestBody UpdateProfileDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(authService.updateProfile(me, dto.getNickname(),
                dto.getAvatarUrl(), dto.getStudentNo()));
    }

    /** 设置「允许陌生人给我发消息」开关(AC-05 护栏偏好) */
    @PutMapping("/stranger-msg")
    public Result<UserVO> setStrangerMsg(@RequestParam("enabled") boolean enabled, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(authService.setStrangerMsgEnabled(me, enabled));
    }

    /** 用户卡片(公开,看他人主页用) */
    @GetMapping("/{id}")
    public Result<UserVO> card(@PathVariable("id") long id) {
        User u = userMapper.selectById(id);
        if (u == null || u.getStatus() == 2) {
            return Result.fail(com.campus.social.common.ResultCode.NOT_FOUND, "用户不存在");
        }
        return Result.ok(UserVoAssembler.of(u));
    }

    /**
     * @ 提及候选:昵称/用户名前缀匹配 + 排除登录人 + 只普通可用用户,限 20。
     * 查询语义(C 组):返回的是"被 kw 匹配到的普通用户(非 me)",不是"我的什么数据"。
     */
    @GetMapping("/mention/candidates")
    public Result<java.util.List<UserVO>> mentionCandidates(
            @RequestParam(value = "kw", required = false) String kw,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String q = kw == null ? "" : kw.trim();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .ne(User::getId, me)
                        .eq(User::getStatus, 0)
                        .eq(User::getRole, 0);
        if (!q.isEmpty()) {
            // 有关键字:昵称/用户名尾匹配
            qw.and(w -> w.likeRight(User::getNickname, q).or().likeRight(User::getUsername, q));
        }
        // 无关键字:走最近活跃的普通用户兜底(前端 @ 按钮场景会用 关注+粉丝 列表,这里只是纯搜索兜底)
        qw.orderByDesc(User::getUpdateTime)
                .last("LIMIT 20");
        java.util.List<User> rows = userMapper.selectList(qw);
        java.util.List<UserVO> vos = rows.stream().map(UserVoAssembler::of).toList();
        return Result.ok(vos);
    }
}
