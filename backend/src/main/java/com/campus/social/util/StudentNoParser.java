package com.campus.social.util;

import com.campus.social.vo.StudentNoParsedVO;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学号解析（openapi AdminVerifyStudentNoParsed）。
 *
 * <p><b>规则来源</b>：5 个真实样本 + 教育部本科专业目录交叉验证，
 * 可执行规格在 {@code scripts/verify-studentno-parse.py}（含 5 正例 6 反例）。
 * 该脚本是本类的验收标准，两者不一致时以脚本为准。
 *
 * <p><b>位布局</b>（只解析前 10 位）：
 * <pre>
 *   位 1-2   入学年份后两位       23 -> 2023
 *   位 3-6   教育部专业类代码     0809=计算机类0802=机械类 0818=交通运输类
 *   位 7-8   专业类内专业序号     与位 3-6 合成 6 位专业代码
 *   位 9-10  班级                 按字符串保留前导零，'02' 不是 '2'
 *   位 11 起 流水号               不解析、不校验长度
 * </pre>
 *
 * <p><b>为什么尾部流水号不校验长度</b>：已知样本里23 级是 4 位、24/25 级是 3 位，
 * 推测 2024 年改过格式，规则未确认。硬编码某个长度会让 2026 级新生
 * 的学号整条解析失败、进而让管理员看到一片空白。这里用 {@code \d{2,4}}
 * 把总长约束在 12-14 位这一区间内，其余一律放行。
 *
 * <p><b>专业代码不含 T后缀</b>：教育部目录里智能车辆工程记作 080214T、
 * 数据科学与大数据技术记作 080910T，而学号里只有数字。
 * 后续做专业名反查时必须先剥掉 T 再匹配，
 * 拿学号里的 6 位数字直接去匹配带 T 的目录会全部落空。
 *
 * <p><b>解析失败绝不填默认值</b>：{@code parsed=false} 时其余四个字段一律 null，
 * 不填 0、不填空串、不猜。展示错误的解析结果比展示「无法解析」危害更大——
 * 前者会让管理员对着一个错的班级做判断。
 *
 * <p><b>本解析结果永不参与审批决策</b>：approve / reject 不读它。
 * 它只是给管理员省去「盯着 14 位数字猜」的时间，
 * 万一规则与真实学号编码不符，误判也只损失便利性，不损失正确性。
 */
public final class StudentNoParser {

    /**
     * 位1-2 年份 | 位 3-6 专业类 | 位 7-8 专业序号 | 位 9-10 班级 | 位 11+ 流水号。
     * 预编译成 static final：{@code pending} 一页最多 50 条，
     * 每条 new 一次 Pattern 会在列表接口上白扔 50 个对象。
     */
    private static final Pattern PATTERN =
            Pattern.compile("^(\\d{2})(\\d{4})(\\d{2})(\\d{2})(\\d{2,4})$");

    /** 本科专业代码是 2000 年后启用的，学号前两位按 20xx 还原 */
    private static final int CENTURY_PREFIX = 2000;

    private StudentNoParser() {
    }

    /**
     * 解析学号。{@code studentNo} 为 null 或不匹配时返回 {@code parsed=false} 的对象，
     * 其余字段全为 null。
     */
    public static StudentNoParsedVO parse(String studentNo) {
        if (studentNo == null) {
            return StudentNoParsedVO.unparsed();
        }
        Matcher m = PATTERN.matcher(studentNo);
        if (!m.matches()) {
            return StudentNoParsedVO.unparsed();
        }
        String year = m.group(1);
        String majorClassCode = m.group(2);
        String majorSeq = m.group(3);
        String classNo = m.group(4);
        // 流水号 m.group(5) 按规则不解析，此处刻意不取
        return StudentNoParsedVO.parsed(CENTURY_PREFIX + Integer.parseInt(year),
                majorClassCode, majorClassCode + majorSeq, classNo);
    }
}