package com.campus.social.service.impl;

import com.campus.social.vo.VisitDayStatVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 访问统计纯函数测试（2026-11-14）。
 *
 * <p>停留秒数封顶、入口清洗、中位数这些规则一旦被改错，
 * 管理总览会安静地显示错数字，所以单独覆盖，不依赖数据库。
 */
class VisitServiceImplTest {

    @Test
    void clampRejectsNegativeAndNull() {
        assertEquals(0, VisitServiceImpl.clampActiveSeconds(null, 100));
        assertEquals(0, VisitServiceImpl.clampActiveSeconds(-3, 100));
        assertEquals(0, VisitServiceImpl.clampActiveSeconds(0, 100));
    }

    @Test
    void clampKeepsReportedValueWithinWallClock() {
        assertEquals(42, VisitServiceImpl.clampActiveSeconds(42, 100));
    }

    @Test
    void clampCapsAtWallClockPlusGrace() {
        // 进入才 10 秒，客户端报 9999 秒 → 只能算 15 秒
        assertEquals(15, VisitServiceImpl.clampActiveSeconds(9999, 10));
    }

    @Test
    void clampCapsAtFourHours() {
        assertEquals(VisitServiceImpl.MAX_ACTIVE_SECONDS,
                VisitServiceImpl.clampActiveSeconds(VisitServiceImpl.MAX_ACTIVE_SECONDS + 1, 10 * 3600));
    }

    @Test
    void cleanTextTrimsControlsAndTruncates() {
        assertNull(VisitServiceImpl.cleanText(null, 10));
        assertNull(VisitServiceImpl.cleanText("   ", 10));
        assertEquals("a b c", VisitServiceImpl.cleanText("  a\tb\nc  ", 10));
        assertEquals("abcde", VisitServiceImpl.cleanText("abcdefgh", 5));
    }

    @Test
    void cleanPathDropsQueryAndHash() {
        assertEquals("/guest", VisitServiceImpl.cleanPath("/guest?from=card1#top"));
        assertEquals("/post/1", VisitServiceImpl.cleanPath("post/1?x=1"));
        assertEquals("/", VisitServiceImpl.cleanPath(""));
        assertEquals("/", VisitServiceImpl.cleanPath("?from=card1"));
    }

    @Test
    void averageIgnoresNullsAndRounds() {
        assertEquals(0, VisitServiceImpl.averageSeconds(null));
        assertEquals(0, VisitServiceImpl.averageSeconds(Collections.emptyList()));
        assertEquals(15, VisitServiceImpl.averageSeconds(Arrays.asList(10, 20)));
        assertEquals(10, VisitServiceImpl.averageSeconds(Arrays.asList(10, null, 10)));
    }

    @Test
    void medianHandlesOddEvenAndEmpty() {
        assertEquals(0, VisitServiceImpl.medianSeconds(null));
        assertEquals(0, VisitServiceImpl.medianSeconds(Collections.emptyList()));
        assertEquals(20, VisitServiceImpl.medianSeconds(Arrays.asList(30, 10, 20)));
        assertEquals(25, VisitServiceImpl.medianSeconds(Arrays.asList(10, 20, 30, 40)));
        assertEquals(15, VisitServiceImpl.medianSeconds(Arrays.asList(20, 10)));
    }

    @Test
    void shortSessionsUseTenSecondThreshold() {
        List<Integer> values = Arrays.asList(0, 9, 10, 60, null);
        assertEquals(2, VisitServiceImpl.countShort(values));
        assertEquals(0, VisitServiceImpl.countShort(null));
    }

    @Test
    void fillTrendAlwaysReturnsSevenDaysEndingToday() {
        List<VisitDayStatVO> trend = VisitServiceImpl.fillTrend(
                null, LocalDate.of(2026, 11, 14), 7);
        assertEquals(7, trend.size());
        assertEquals("2026-11-08", trend.get(0).getDate());
        assertEquals("2026-11-14", trend.get(6).getDate());
        assertEquals(0, trend.get(6).getScanVisitors());
    }

    @Test
    void fillTrendKeepsRowsAndFillsMissingDays() {
        VisitDayStatVO row = new VisitDayStatVO();
        row.setDate("2026-11-12");
        row.setScanVisitors(9);
        List<VisitDayStatVO> trend = VisitServiceImpl.fillTrend(
                List.of(row), LocalDate.of(2026, 11, 14), 7);

        assertEquals(7, trend.size());
        assertEquals("2026-11-12", trend.get(4).getDate(), "倒数第 3 天应是 11-12");
        assertEquals(9, trend.get(4).getScanVisitors(), "已有数据不能被补零覆盖");
        assertEquals(0, trend.get(3).getScanVisitors(), "缺失的天补零");
    }
}