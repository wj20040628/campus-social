package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.dto.VisitReportDTO;
import com.campus.social.entity.VisitLog;
import com.campus.social.mapper.VisitLogMapper;
import com.campus.social.service.VisitService;
import com.campus.social.vo.GuestVisitVO;
import com.campus.social.vo.VisitDayStatVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 访客访问记录实现（2026-11-14）。
 *
 * <p>停留只算「页面在前台」的累计时长：心跳与结束上报都带客户端
 * 累计秒数，服务端用「进入至今的墙钟时长 + 5 秒」封顶，客户端虚报
 * 再大也超不过真实经过的时间。
 */
@Service
@RequiredArgsConstructor
public class VisitServiceImpl implements VisitService {

    /** 单个会话最多累计 4 小时，防止长期挂着的标签页把均值拉得离谱 */
    static final int MAX_ACTIVE_SECONDS = 4 * 3600;

    /** 最后心跳超过 60 秒即按已结束结算，覆盖关闭页面时结束上报没送达 */
    static final int SETTLE_AFTER_SECONDS = 60;

    /** 停留不足 10 秒算短时离开 */
    static final int SHORT_SECONDS = 10;

    /** 趋势固定取近 7 天（含今天），前端不需要再补日期 */
    static final int TREND_DAYS = 7;

    private final VisitLogMapper visitLogMapper;

    @Override
    public void enter(VisitReportDTO dto, String ip, String userAgent) {
        String visitorId = cleanText(dto.getVisitorId(), 36);
        String sessionId = cleanText(dto.getSessionId(), 36);
        if (visitorId == null || sessionId == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        VisitLog row = new VisitLog();
        row.setVisitorId(visitorId);
        row.setSessionId(sessionId);
        row.setIsGuest(Boolean.TRUE.equals(dto.getGuest()) ? 1 : 0);
        row.setEntryPath(cleanPath(dto.getEntryPath()));
        row.setSource(cleanText(dto.getSource(), 32));
        row.setIp(cleanText(ip, 64));
        row.setUa(cleanText(userAgent, 255));
        row.setEnteredAt(now);
        row.setLastSeenAt(now);
        row.setActiveSeconds(0);
        row.setEnded(0);
        visitLogMapper.insertIgnore(row);
    }

    @Override
    public void ping(VisitReportDTO dto) {
        String sessionId = cleanText(dto.getSessionId(), 36);
        if (sessionId == null) {
            return;
        }
        VisitLog existing = visitLogMapper.selectOne(
                new LambdaQueryWrapper<VisitLog>().eq(VisitLog::getSessionId, sessionId));
        if (existing == null) {
            // enter 丢了（例如上报被拦截）时不凭 ping 造行：没有入口与来源，
            // 造出来的行会让「扫码人数」口径失真
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long elapsed = Duration.between(existing.getEnteredAt(), now).getSeconds();
        int reported = clampActiveSeconds(dto.getActiveSeconds(), elapsed);
        int active = Math.max(valueOrZero(existing.getActiveSeconds()), reported);
        int guest = Boolean.TRUE.equals(dto.getGuest()) ? 1 : valueOrZero(existing.getIsGuest());
        int ended = Boolean.TRUE.equals(dto.getEnded()) ? 1 : 0;
        visitLogMapper.updateProgress(sessionId, now, active, guest, ended);
    }

    @Override
    public GuestVisitVO summary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime staleBefore = now.minusSeconds(SETTLE_AFTER_SECONDS);

        GuestVisitVO vo = new GuestVisitVO();
        vo.setTodayScanVisitors(visitLogMapper.countScanVisitors(todayStart, tomorrowStart));
        vo.setTodayScanSessions(visitLogMapper.countScanSessions(todayStart, tomorrowStart));
        vo.setTodayGuestSessions(visitLogMapper.countGuestSessions(todayStart, tomorrowStart));

        List<Integer> settled = visitLogMapper.selectSettledSeconds(todayStart, tomorrowStart, staleBefore);
        vo.setTodaySettledSessions(settled.size());
        vo.setTodayShortSessions(countShort(settled));
        vo.setAvgDwellSeconds(averageSeconds(settled));
        vo.setMedianDwellSeconds(medianSeconds(settled));

        vo.setYesterdayScanVisitors(visitLogMapper.countScanVisitors(yesterdayStart, todayStart));
        vo.setYesterdayScanSessions(visitLogMapper.countScanSessions(yesterdayStart, todayStart));
        vo.setYesterdayGuestSessions(visitLogMapper.countGuestSessions(yesterdayStart, todayStart));
        vo.setSources(visitLogMapper.selectSources(todayStart, tomorrowStart));
        LocalDateTime trendStart = todayStart.minusDays(TREND_DAYS - 1);
        vo.setTrend(fillTrend(
                visitLogMapper.selectDailyTrend(trendStart, tomorrowStart),
                LocalDate.now(), TREND_DAYS));
        return vo;
    }

    /**
     * 把数据库「有数据才有行」的结果补成连续 N 天，最后一项是 endDate。
     *
     * <p>趋势图不能因为某天没人扫码就少一根柱子、导致日期错位；
     * 缺失的天补零，前端按固定 7 列渲染。纯函数，单独覆盖。
     */
    static List<VisitDayStatVO> fillTrend(List<VisitDayStatVO> rows, LocalDate endDate, int days) {
        Map<String, VisitDayStatVO> byDate = new HashMap<>();
        if (rows != null) {
            for (VisitDayStatVO row : rows) {
                if (row != null && row.getDate() != null) {
                    byDate.put(row.getDate(), row);
                }
            }
        }
        List<VisitDayStatVO> trend = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            String date = endDate.minusDays(i).toString();
            VisitDayStatVO row = byDate.get(date);
            if (row == null) {
                row = new VisitDayStatVO();
                row.setDate(date);
            }
            trend.add(row);
        }
        return trend;
    }

    /**
     * 客户端上报的停留秒数封顶。
     *
     * <p>上限 = 进入至今的墙钟秒数 + 5 秒（容忍一次网络往返）。
     * 页面前台时间不可能超过墙钟时间，所以伪造再大的数字也无效。
     */
    static int clampActiveSeconds(Integer reported, long elapsedSeconds) {
        if (reported == null || reported <= 0) {
            return 0;
        }
        long cap = Math.max(0L, elapsedSeconds) + 5L;
        return (int) Math.min(Math.min(reported.longValue(), MAX_ACTIVE_SECONDS), cap);
    }

    /** 平均停留（秒），四舍五入 */
    static long averageSeconds(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        int count = 0;
        for (Integer value : values) {
            if (value != null) {
                sum += Math.max(0, value);
                count++;
            }
        }
        return count == 0 ? 0 : Math.round((double) sum / count);
    }

    /** 中位停留（秒）；偶数个取中间两个的平均，四舍五入 */
    static long medianSeconds(List<Integer> values) {
        List<Integer> sorted = sortedNonNegative(values);
        if (sorted.isEmpty()) {
            return 0;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    /** 停留不足 10 秒的会话数 */
    static long countShort(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .filter(value -> value != null && value < SHORT_SECONDS)
                .count();
    }

    static String cleanText(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("\\p{Cntrl}", " ").trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 入口只留路径：去掉 query / hash，保证 /guest 的统计口径不被参数污染 */
    static String cleanPath(String raw) {
        String value = cleanText(raw, 64);
        if (value == null) {
            return "/";
        }
        int end = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            end = Math.min(end, hash);
        }
        value = value.substring(0, end).trim();
        if (value.isEmpty()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static List<Integer> sortedNonNegative(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Integer> sorted = new ArrayList<>(values.size());
        for (Integer value : values) {
            if (value != null) {
                sorted.add(Math.max(0, value));
            }
        }
        sorted.sort(Integer::compareTo);
        return sorted;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}