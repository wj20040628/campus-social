package com.campus.social.service;

import com.campus.social.dto.CampusEmailConfirmDTO;
import com.campus.social.dto.CampusEmailSendDTO;
import com.campus.social.dto.SchoolProfileDTO;
import com.campus.social.dto.StudentNoVerifyDTO;
import com.campus.social.vo.SchoolProfileVO;
import com.campus.social.vo.StudentNoVerifyVO;
import com.campus.social.vo.VerifyStatusVO;

/**
 * 校内认证（F2）。
 *
 * <p>两条路径，性质不同：
 * <ul>
 *   <li><b>校园邮箱</b>：自证。域名能收到验证码即证明归属，同步完成</li>
 *   <li><b>学号</b>：不可自证，进人工队列。队列就是 {@code user} 表本身
 *       （{@code verify_status = 2}），不建新表——ARCHITECTURE §3 Q9.4 的裁决</li>
 * </ul>
 */
public interface CampusVerifyService {

    /** 设置/更新 学校学院年级（引导第 1 步） */
    SchoolProfileVO saveSchoolProfile(long userId, SchoolProfileDTO dto);

    /** 发起校园邮箱验证码。域名白名单不通过则 422 */
    String sendCampusEmailCode(long userId, CampusEmailSendDTO dto);

    /** 确认校园邮箱验证码，通过后立即认证 */
    VerifyStatusVO confirmCampusEmail(long userId, CampusEmailConfirmDTO dto);

    /** 提交学号认证，进入人工核验队列 */
    StudentNoVerifyVO submitStudentNo(long userId, StudentNoVerifyDTO dto);

    /** 当前认证状态 */
    VerifyStatusVO status(long userId);
}