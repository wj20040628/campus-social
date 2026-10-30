package com.campus.social.service;

import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;

/**
 * 校园信息登记（F2）。
 *
 * <p>2026-10-30 起仅作为可选资料，不授予任何功能权限。
 * 填写路径只剩学号：格式合法时与所选年级/专业做一致性比对；
 * 一致直接登记，不一致进核对队列。队列就是 {@code user} 表本身
 * （{@code verify_status = 2}），不建新表。
 *
 * <p>校园邮箱验证码路径已于 2026-10-30 删除：学生没有学校发放的
 * 校园邮箱，现有邮箱是 QQ / 网易等个人邮箱，域名白名单无法作为校内凭证。
 *
 * <p>学校不提供学生名单，上述结果都不能作为「此人确为该校学生」的证明。
 */
public interface CampusVerifyService {

    /** 设置/更新 学校学院年级（引导第 1 步） */
    SchoolProfileVO saveSchoolProfile(long userId, SchoolProfileDTO dto);

    /** 提交学号：一致则直接登记，不一致转人工核对 */
    StudentNoVerifyVO submitStudentNo(long userId, StudentNoVerifyDTO dto);

    /** 当前校园信息状态（仅资料展示） */
    VerifyStatusVO status(long userId);
}