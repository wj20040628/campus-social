package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 学号解析结果（openapi AdminVerifyStudentNoParsed）。
 *
 * <p>让管理员在手机上不必盯着一串 14 位数字猜：入学年份、专业、班级
 * 直接拆好给他看。这是纯展示信息，<b>不参与任何审批判断</b>。
 *
 * <p><b>parsed=false 时其余四个字段必须全为 null</b>。
 * 调用方禁止用默认值或兜底逻辑填补——给管理员看一个错的班级，
 * 比明明白白告诉他「解析不出来」危险得多。
 *
 * <p>{@code classNo} 与 {@code majorClassCode} 必须是 String：
 * 班级位 '02' 转成 int 会变成 '2'，管理员会以为那是 2 班。
 */
@Data
public class StudentNoParsedVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 2000 + 学号前两位。解析失败时为 null */
    private Integer entranceYear;

    /** 位 3-6，教育部专业类代码，如 '0809'。解析失败时为 null */
    private String majorClassCode;

    /**
     * 位 3-8，教育部 6 位本科专业代码，如 '080214'。
     * <b>不含特设专业的 T 后缀</b>：教育部目录里智能车辆工程记作 080214T，
     * 而学号里只有数字，查表时须剥掉 T 再匹配。解析失败时为 null。
     */
    private String majorCode;

    /** 位 9-10 班级，保留前导零（'02'）。解析失败时为 null */
    private String classNo;

    /** false 表示学号无法解析，此时上面四个字段一律为 null */
    private boolean parsed;

    /** 解析成功。{@code majorCode} 传的是「专业类 + 专业序号」的6 位拼接值 */
    public static StudentNoParsedVO parsed(int entranceYear, String majorClassCode,
                                           String majorCode, String classNo) {
        StudentNoParsedVO vo = new StudentNoParsedVO();
        vo.entranceYear = entranceYear;
        vo.majorClassCode = majorClassCode;
        vo.majorCode = majorCode;
        vo.classNo = classNo;
        vo.parsed = true;
        return vo;
    }

    /**
     * 解析失败。四字段留null，<b>不填任何默认值</b>：
     * 展示错误的解析结果比展示「无法解析」危害更大，
     * 它会让管理员对着一个错的班级做出判断。
     */
    public static StudentNoParsedVO unparsed() {
        return new StudentNoParsedVO();
    }
}