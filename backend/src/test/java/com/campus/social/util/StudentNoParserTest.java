package com.campus.social.util;

import com.campus.social.vo.StudentNoParsedVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 学号解析验收（对应 scripts/verify-studentno-parse.py 的 5 正例 6 反例）。
 *
 * <p>纯单元测试：不连库、不连 Redis、不起 Spring 容器，
 * 因此可以在任何环境下跑，也能在规则被改动时立刻失败。
 *
 * <p>断言写成<b>逐字段</b>而不是「等于某个对象」：
 * 后者依赖 {@code equals}，而 VO 用的是 Lombok {@code @Data}，
 * 一个字段漏赋值时若恰好与期望对象全等就会假通过。
 */
class StudentNoParserTest {

    // 5 个真实样本。expected 顺序：入学年份、专业类、专业代码、班级
    @Test
    void 五个真实样本解析正确() {
        assertParsed("23080901060536", 2023, "0809", "080901", "06");
        assertParsed("2408091003508", 2024, "0809", "080910", "03");
        assertParsed("2508021402549", 2025, "0802", "080214", "02");
        assertParsed("2508021401546", 2025, "0802", "080214", "01");
        assertParsed("2408180102539", 2024, "0818", "081801", "02");
    }

    /** 6 个反例：parsed=false 且其余四字段全 null，不填任何默认值 */
    @Test
    void 六类非法学号一律拒绝且不带任何字段() {
        assertRejected("", "空串");
        assertRejected("23080901060", "11 位，短于下界");
        assertRejected("230809010605361", "15 位，长于上界");
        assertRejected("2308090106053a", "含非数字字符");
        assertRejected("  23080901060536", "含空格");
        assertRejected("2308090106053.6", "含小数点");
    }

    /**
     * 班级位必须按字符串保留前导零。
     * 转成 int 会让 '02' 变成 '2'，管理员会以为那是 2 班——
     * 而它其实是 02 班。这个 bug 编译期和肉眼 review 都看不出来。
     */
    @Test
    void 班级位保留前导零() {
        StudentNoParsedVO vo = StudentNoParser.parse("23080901060536");
        assertEquals("06", vo.getClassNo());
        assertNotEquals("6", vo.getClassNo());
    }

    /**
     * 专业代码不含 T 后缀。
     * 教育部目录里智能车辆工程是 080214T、大数据是 080910T，
     * 若实现里补了个 T，下游拿 6 位数字去匹配目录会全部落空。
     */
    @Test
    void 专业代码不含T后缀() {
        assertEquals("080214", StudentNoParser.parse("2508021402549").getMajorCode());
        assertEquals("080910", StudentNoParser.parse("2408091003508").getMajorCode());
    }

    /** null 输入不能抛 NPE：待审列表的 student_no 理论上非空，但不该因此让整页 500 */
    @Test
    void null输入返回未解析而不是抛异常() {
        assertRejected(null, "null");
    }

    /**
     * 流水号长度不校验：13 位（24/25 级）与 14 位（23 级）都应通过。
     * 这是刻意的宽松——已知样本里两种长度都真实存在，
     * 硬卡某个长度会让另一种年级的学号整条解析失败。
     */
    @Test
    void 十三位与十四位都应解析成功() {
        assertParsed("2408091003508", 2024, "0809", "080910", "03");
        assertParsed("23080901060536", 2023, "0809", "080901", "06");
    }

    // ------------------------------------------------------------ 断言helper

    private void assertParsed(String raw, int year, String classCode, String majorCode, String classNo) {
        StudentNoParsedVO vo = StudentNoParser.parse(raw);
        assertTrue(vo.isParsed(), "应解析成功: " + raw);
        assertEquals(year, vo.getEntranceYear(), "入学年份: " + raw);
        assertEquals(classCode, vo.getMajorClassCode(), "专业类: " + raw);
        assertEquals(majorCode, vo.getMajorCode(), "专业代码: " + raw);
        assertEquals(classNo, vo.getClassNo(), "班级: " + raw);
    }

    private void assertRejected(String raw, String why) {
        StudentNoParsedVO vo = StudentNoParser.parse(raw);
        assertFalse(vo.isParsed(), "应解析失败: " + raw + " (" + why + ")");
        assertNull(vo.getEntranceYear(), why + " -> 入学年份必须为 null，不得填默认值");
        assertNull(vo.getMajorClassCode(), why + " -> 专业类必须为 null");
        assertNull(vo.getMajorCode(), why + " -> 专业代码必须为 null");
        assertNull(vo.getClassNo(), why + " -> 班级必须为 null");
    }
}