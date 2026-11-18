package com.campus.social.integration;

import com.campus.social.dto.VisitReportDTO;
import com.campus.social.service.VisitService;
import com.campus.social.vo.GuestVisitVO;
import com.campus.social.vo.VisitDayStatVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访客访问记录落库与聚合（2026-11-14）。
 *
 * <p>覆盖三件容易回归的事：
 * <ol>
 *   <li>enter 幂等：页面重载 / 网络重试不能产生第二行；</li>
 *   <li>客户端虚报停留时长会被服务端按墙钟封顶；</li>
 *   <li>总览能数到刚写入的扫码访客。</li>
 * </ol>
 * 自播种自清理，不依赖既有数据。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class VisitLogIntegrationTest {

    @Autowired
    private VisitService visitService;

    @Autowired
    private JdbcTemplate jdbc;

    private final String visitorId = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM visit_log WHERE session_id = ?", sessionId);
    }

    @Test
    void enterIsIdempotentAndPingClampsDwell() {
        VisitReportDTO enter = new VisitReportDTO();
        enter.setVisitorId(visitorId);
        enter.setSessionId(sessionId);
        enter.setEntryPath("/guest?from=card1");
        enter.setSource("card1");
        enter.setGuest(true);
        enter.setActiveSeconds(0);
        enter.setEnded(false);

        visitService.enter(enter, "127.0.0.1", "JUnit UA");
        visitService.enter(enter, "127.0.0.1", "JUnit UA");

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visit_log WHERE session_id = ?", Integer.class, sessionId);
        assertEquals(1, rows, "同一 session_id 只能有一行");

        VisitReportDTO ping = new VisitReportDTO();
        ping.setVisitorId(visitorId);
        ping.setSessionId(sessionId);
        ping.setGuest(true);
        ping.setActiveSeconds(9999);
        ping.setEnded(true);
        visitService.ping(ping);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT active_seconds, ended, entry_path FROM visit_log WHERE session_id = ?", sessionId);
        int active = ((Number) row.get("active_seconds")).intValue();
        assertTrue(active >= 0 && active <= 10, "进入不足 1 毫秒，封顶应为墙钟 + 5 秒");
        assertEquals(1, ((Number) row.get("ended")).intValue());
        assertEquals("/guest", row.get("entry_path"), "入口路径应去掉 query");

        GuestVisitVO summary = visitService.summary();
        assertTrue(summary.getTodayScanVisitors() >= 1, "总览应数到本次扫码访客");
        assertTrue(summary.getTodayScanSessions() >= 1, "总览应数到本次扫码会话");

        assertEquals(7, summary.getTrend().size(), "趋势固定返回连续 7 天");
        VisitDayStatVO today = summary.getTrend().get(6);
        assertEquals(LocalDate.now().toString(), today.getDate(), "最后一项必须是今天");
        assertTrue(today.getScanVisitors() >= 1, "今日趋势应数到本次扫码访客");
        assertTrue(today.getGuestSessions() >= 1, "今日趋势应数到本次游客会话");
    }
}