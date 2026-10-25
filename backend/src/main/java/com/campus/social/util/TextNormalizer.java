package com.campus.social.util;

/**
 * 敏感词检测用的文本归一化（2026-10-24 新增）。
 *
 * <p><b>解决什么</b>：DFA 只能逐字符精确匹配，而绕过只需在词里动手脚——
 * 「微 信」（拆字空格）、「ｗｅｉｘｉｎ」（全角）、「微\u200b信」（零宽字符）
 * 都能从词表眼皮底下溜走。归一化把这些变体压回同一形态再扫。
 *
 * <p><b>做什么</b>：
 * <ul>
 *   <li>全角 ASCII（U+FF01..FF5E）→ 半角；全角空格 U+3000 删除</li>
 *   <li>零宽/软连字符等不可见字符（U+200B..200F、U+FEFF、U+00AD、U+2060..2064）删除</li>
 *   <li>所有空白字符删除（中文语境的拆字空格是最常见绕过）</li>
 * </ul>
 * 大小写不在此处理——DFA 匹配侧已经统一 toLowerCase。
 *
 * <p><b>明确的边界（为什么谐音/拼音不在这里做）</b>：
 * 谐音（「薇」代「微」）与拼音缩写（wx）是词义级变体，需要映射表才能还原，
 * 映射表的维护成本与误报风险都远高于字符级归一化——那属于词库运营的职责
 * （直接把常见变体加进 sensitive_word 表即可），不是代码该硬编码的。
 *
 * <p><b>只用于检测，不用于存储</b>：去空白会把英文「not bad」变成「notbad」，
 * 两个无害词可能拼出敏感词造成罕见误报——检测侧误报只是多送一次人工审，
 * 存储侧改写则会破坏原文，故 replaceAll/matchedLevel1Words 始终对原文操作。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** 归一化文本：全角→半角、去不可见字符、去空白。null 返回空串。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                // 全角 ASCII 区：偏移 0xFEE0 即对应半角(U+FF01 '!' → '!')
                c = (char) (c - 0xFEE0);
            }
            if (c == 0x3000 || isInvisible(c) || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 零宽连接符/非连接符、方向标记、BOM、软连字符等「看不见但占位」的字符 */
    private static boolean isInvisible(char c) {
        return (c >= 0x200B && c <= 0x200F)
                || c == 0xFEFF
                || c == 0x00AD
                || (c >= 0x2060 && c <= 0x2064);
    }
}
