package com.campus.social.service.support;

import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.SchoolMajor;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.DictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.campus.social.common.ResultCode.STUDENT_NO_MISMATCH;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 学号与本人学校归属的六层硬校验（2026-10-28 从 CampusVerifyServiceImpl 抽出）。
 *
 * <p><b>为什么必须抽出来共用</b>：提交学号有两条入口——
 * {@code POST /user/verify/student-no} 与 {@code PUT /user/me} 改学号。
 * 此前只有前者做六层校验；设置页可以把「不是纯数字」「专业码对不上」的
 * 学号直接塞进人工队列。两条入口必须是同一把尺子。
 *
 * <p><b>顺序</b>：
 * <ol>
 *   <li>纯数字</li>
 *   <li>长度：23 级 14 位、24 级起 13 位</li>
 *   <li>年级匹配：前两位 == 所选年级入学年份后两位</li>
 *   <li>层次匹配：学号层次标记(本科 05/5、专科 06/6) == 所选专业层次</li>
 *   <li>专业编码匹配：位 3-8（6 位）== 所选专业 code</li>
 *   <li>班内序号：最后两位 &lt; 70</li>
 * </ol>
 *
 * <p>第 3~6 层对外统一只说「不匹配」，细节进日志——逐层解释等于把学号编码
 * 规则讲给攻击者，六层随即失去意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentNoProfileValidator {

    /** 班内序号上限（末两位 00~69） */
    private static final int MAX_CLASS_RANK = 69;

    private final DictService dictService;

    public void validate(String studentNo, User me) {
        SchoolMajor major = dictService.requireSelectableMajor(me.getMajorId(), me.getDeptId());
        SchoolGrade grade = dictService.requireSelectableGrade(me.getGradeId());
        if (major.getCode() == null || major.getCode().length() != 6) {
            // 字典数据缺陷,不是用户填错:交人工复核而不是拿一个坏 code 比,避免误拒真学生
            throw new BusinessException(UNPROCESSABLE, "专业编码缺失，请联系管理员核对专业字典");
        }

        // 第 1 层：纯数字
        if (!studentNo.matches("\\d+")) {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号需为纯数字");
        }
        // 第 2 层：长度与入学年份匹配
        int year = Integer.parseInt(studentNo.substring(0, 2));
        int len = studentNo.length();
        if (len == 14) {
            if (year != 23) {
                throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度与入学年份不匹配（23 级为 14 位）");
            }
        } else if (len == 13) {
            if (year < 24) {
                throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度与入学年份不匹配（24 级起为 13 位）");
            }
        } else {
            throw new BusinessException(STUDENT_NO_MISMATCH, "学号长度须为 13 或 14 位");
        }
        // 第 3 层：年级匹配
        int gradeSuffix = grade.getEnrollYear() % 100;
        if (year != gradeSuffix) {
            mismatch(me, "L3 年级", "学号前两位=" + year + " vs 年级入学年份后两位=" + gradeSuffix);
        }
        // 第 4 层：层次匹配
        int marker = parseLevelMarker(studentNo);
        if (marker != major.getLevel()) {
            mismatch(me, "L4 层次", "学号层次标记=" + marker + " vs 专业层次=" + major.getLevel());
        }
        // 第 5 层：专业编码匹配
        String majorCode = studentNo.substring(2, 8);
        if (!majorCode.equals(major.getCode())) {
            mismatch(me, "L5 专业码", "学号位3-8=" + majorCode + " vs 专业 code=" + major.getCode());
        }
        // 第 6 层：班内序号 < 70
        int seq = Integer.parseInt(studentNo.substring(len - 2));
        if (seq > MAX_CLASS_RANK) {
            mismatch(me, "L6 班内序号", "末两位=" + seq + " 上限=" + MAX_CLASS_RANK);
        }
    }

    /**
     * 第 3~6 层的统一失败出口：对外一句「不匹配」，细节进日志。
     * 第 1/2 层不收敛：规则已在注册正则里公开，不构成额外泄露。
     */
    private void mismatch(User me, String layer, String detail) {
        log.warn("studentNo verify rejected: user={}, layer={}, detail={}",
                me.getId(), layer, detail);
        throw new BusinessException(STUDENT_NO_MISMATCH);
    }

    /**
     * 解析学号层次标记为本/专科枚举。
     *
     * @return 1=本科 2=专科 0=无法识别（14 位取位 11-12,13 位取位 11）
     */
    private int parseLevelMarker(String studentNo) {
        String marker = studentNo.length() == 14
                ? studentNo.substring(10, 12)
                : studentNo.substring(10, 11);
        if ("05".equals(marker) || "5".equals(marker)) return 1;
        if ("06".equals(marker) || "6".equals(marker)) return 2;
        return 0;
    }
}
