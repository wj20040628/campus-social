package com.campus.social.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextNormalizer 归一化规则（2026-10-24 加,配敏感词变体检测）。
 *
 * <p>每条用例对应一种真实绕过手法;「不变」用例守住归一化不误伤正常文本的底线。
 */
class TextNormalizerTest {

    @Test
    void normalTextUnchanged() {
        assertEquals("周末爬山约起", TextNormalizer.normalize("周末爬山约起"));
        assertEquals("HelloWorld123", TextNormalizer.normalize("HelloWorld123"));
    }

    @Test
    void nullAndEmptySafe() {
        assertEquals("", TextNormalizer.normalize(null));
        assertEquals("", TextNormalizer.normalize(""));
    }

    @Test
    void fullwidthToHalfwidth() {
        // 全角字母/数字/标点 → 半角:「ｗｅｉｘｉｎ」不再从词表溜走
        assertEquals("weixin", TextNormalizer.normalize("ｗｅｉｘｉｎ"));
        assertEquals("a1!", TextNormalizer.normalize("ａ１！"));
    }

    @Test
    void splitSpacesRemoved() {
        // 拆字空格(含全角空格 U+3000):「微 信」与「微信」压成同一形态
        assertEquals("微信", TextNormalizer.normalize("微 信"));
        assertEquals("微信", TextNormalizer.normalize("微　信"));
        assertEquals("卖号", TextNormalizer.normalize(" 卖 \n 号 "));
    }

    @Test
    void invisibleCharsRemoved() {
        // 零宽字符/软连字符/BOM:看不见但占位,「微\u200b信」=「微信」
        assertEquals("微信", TextNormalizer.normalize("微\u200b信"));
        assertEquals("微信", TextNormalizer.normalize("微\uFEFF信"));
        assertEquals("微信", TextNormalizer.normalize("微\u00AD信"));
    }

    @Test
    void mixedVariantsCollapse() {
        // 混合变体:全角 + 拆字 + 零宽一起上
        assertEquals("weixin加我", TextNormalizer.normalize("ｗ ｅ\u200bｉｘｉｎ 加 我"));
    }

    @Test
    void realWorldShape() {
        // 与 SensitiveWordFilter 的协作形态:归一化产物与词表原形一致即可被 DFA 命中
        String word = "代写论文";
        assertTrue(TextNormalizer.normalize("需要 代\u200b写 论 文 的私聊").contains(word));
        assertFalse(TextNormalizer.normalize("今天天气不错").contains(word));
    }
}
