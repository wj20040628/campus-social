package com.campus.social.service.support;

import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.SchoolMajor;
import com.campus.social.entity.User;
import com.campus.social.service.DictService;
import com.campus.social.util.StudentNoParser;
import com.campus.social.vo.StudentNoParsedVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 学号与学校归属的一致性检查（2026-10-30）。
 *
 * <p>只用于决定「校园信息直接登记」还是「进入管理员核对队列」，
 * <b>不再拒绝用户</b>，也不参与任何功能权限判定。
 * 规则或字典不准时，误判的代价只是多一条待核对记录。
 */
@Component
@RequiredArgsConstructor
public class StudentNoConsistencyChecker {

    private final DictService dictService;

    public boolean matches(String studentNo, User user) {
        StudentNoParsedVO parsed = StudentNoParser.parse(studentNo);
        if (!parsed.isParsed() || parsed.getEntranceYear() == null || parsed.getMajorCode() == null) {
            return false;
        }
        try {
            SchoolGrade grade = dictService.requireSelectableGrade(user.getGradeId());
            SchoolMajor major = dictService.requireSelectableMajor(user.getMajorId(), user.getDeptId());
            return parsed.getEntranceYear().equals(grade.getEnrollYear())
                    && major.getCode() != null
                    && parsed.getMajorCode().equals(major.getCode().trim());
        } catch (RuntimeException e) {
            // 字典缺失/停用不是用户的错：转到管理员核对，不在这里下结论
            return false;
        }
    }
}
