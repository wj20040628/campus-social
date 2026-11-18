package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.CampusVerifyService;
import com.campus.social.service.RegisterService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.AdminRegistrationAlertService;
import com.campus.social.service.support.TokenService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import static com.campus.social.common.ResultCode.*;

/**
 * 注册建号。
 *
 * <p>2026-10-21 口径：邮箱必填且需验证码验证所有权；学号选填，填了走归属与核验流程，
 * 不填则注册为未填写状态。验证码登录不再自动建号（2026-11-14），建号只走这里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements RegisterService {

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final AuditService auditService;
    private final CampusVerifyService campusVerifyService;
    private final TokenService tokenService;
    private final AdminRegistrationAlertService adminRegistrationAlertService;

    @Override
    @Transactional
    public TokenPairVO register(RegisterDTO dto) {
        String email = dto.getEmail() == null ? null : dto.getEmail().trim().toLowerCase();
        if (email == null || email.isEmpty()) {
            throw new BusinessException(UNPROCESSABLE, "请填写邮箱");
        }
        // 邮箱是登录主键，必须先验证所有权；type 与 sendCode 的默认值一致
        verifyCodeService.verify(email, dto.getEmailCode(), "register");

        User byEmail = userMapper.selectByEmail(email);
        if (byEmail != null) {
            throw new BusinessException(EMAIL_ALREADY_BOUND, "该邮箱已注册");
        }

        String studentNo = dto.getStudentNo() == null ? "" : dto.getStudentNo().trim();
        boolean withStudentNo = !studentNo.isEmpty();
        if (withStudentNo) {
            User existing = userMapper.selectByStudentNo(studentNo);
            if (existing != null) {
                throw new BusinessException(USERNAME_EXISTS, "该学号已注册");
            }
        }

        User user = new User();
        // username 存邮箱：学号记不住、填错改不了，且对外不得显示学号
        user.setUsername(email);
        user.setPasswordHash(JwtUtil.hashPassword(dto.getPassword()));
        // 昵称选填：留空走自动生成
        String nickname = dto.getNickname() == null ? "" : dto.getNickname().trim();
        user.setNickname(nickname.isEmpty() ? autoNickname() : nickname);
        user.setEmail(email);
        user.setStudentNo(withStudentNo ? studentNo : null);
        user.setStatus(0);
        userMapper.insert(user);

        if (withStudentNo) {
            if (dto.getDeptId() == null || dto.getGradeId() == null || dto.getMajorId() == null) {
                throw new BusinessException(SCHOOL_PROFILE_INCOMPLETE,
                        "请完善学校归属（学院 / 本专科 / 专业 / 年级）后再提交学号");
            }
            SchoolProfileDTO profileDto = new SchoolProfileDTO();
            profileDto.setSchoolId(dto.getSchoolId());
            profileDto.setDeptId(dto.getDeptId());
            profileDto.setGradeId(dto.getGradeId());
            profileDto.setMajorId(dto.getMajorId());
            profileDto.setCampus(dto.getCampus());
            campusVerifyService.saveSchoolProfile(user.getId(), profileDto);

            StudentNoVerifyDTO verifyDto = new StudentNoVerifyDTO();
            verifyDto.setStudentNo(studentNo);
            verifyDto.setNameTail("");
            campusVerifyService.submitStudentNo(user.getId(), verifyDto);
        }

        auditService.record(user.getId(), "register", "user", user.getId(),
                "email=" + UserVoAssembler.maskUsername(email)
                        + (withStudentNo ? ", studentNo=" + UserVoAssembler.maskUsername(studentNo) : ", 未认证"),
                null);

        log.info("register: uid={}, email={}, studentNo={}, verified={}",
                user.getId(), UserVoAssembler.maskUsername(email),
                withStudentNo ? UserVoAssembler.maskUsername(studentNo) : "-", withStudentNo);
        adminRegistrationAlertService.record(user);
        // 注册默认不勾记住我（记住我是密码登录的优化项）
        return tokenService.issue(user.getId(), user.getUsername(), false);
    }

    /** 「用户」+ 6 位随机，先查重；三次都撞退化到 9 位（并发极小概率） */
    private String autoNickname() {
        for (int i = 0; i < 3; i++) {
            String candidate = "用户" + ThreadLocalRandom.current().nextInt(100000, 1000000);
            if (userMapper.countByNickname(candidate) == 0) {
                return candidate;
            }
        }
        return "用户" + ThreadLocalRandom.current().nextInt(100000000, 1000000000);
    }
}
