package com.campus.social.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.campus.social.common.ResultCode.STUDENT_NO_EXISTS;
import static com.campus.social.common.ResultCode.STUDENT_NO_MISMATCH;

/**
 * 改学号（2026-10-30 简化）。
 *
 * <p>校园信息不再决定功能权限，所以这里只做三件事：
 * 格式校验、唯一性校验、状态迁移。
 * 年级/专业编码一致性只决定「直接登记」还是「进管理员核对队列」，
 * 不再拒绝用户，也不会因为规则误判让人少一个功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentNoChangeSupport {

    private static final int VERIFY_OK = 1;
    private static final int VERIFY_PENDING = 2;

    private final UserMapper userMapper;
    private final StudentNoConsistencyChecker consistency;

    public void apply(User user, String studentNo) {
        if (!studentNo.matches("\\d{13,14}")) {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号需为 13~14 位数字");
        }
        requireUnused(studentNo, user.getId());

        user.setStudentNo(studentNo);
        userMapper.markVerifyPending(user.getId(), studentNo);
        user.setVerifyStatus(VERIFY_PENDING);

        if (consistency.matches(studentNo, user)) {
            userMapper.approveVerify(user.getId());
            user.setVerifyStatus(VERIFY_OK);
            log.info("campus info registered by profile edit: user={}", user.getId());
        } else {
            log.info("campus info pending review by profile edit: user={}", user.getId());
        }
    }

    private void requireUnused(String studentNo, long selfId) {
        Long dup = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStudentNo, studentNo)
                        .ne(User::getId, selfId));
        if (dup != null && dup > 0) {
            throw new BusinessException(STUDENT_NO_EXISTS, "该学号已被其他账号绑定");
        }
    }
}
