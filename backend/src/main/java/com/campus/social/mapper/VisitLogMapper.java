package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.VisitLog;
import com.campus.social.vo.VisitDayStatVO;
import com.campus.social.vo.VisitSourceCountVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 访客访问记录 Mapper。
 *
 * <p>enter 用 INSERT IGNORE：session_id 是唯一键，页面重载或网络重试时
 * 重复 enter 不应产生第二行。其余字段在 service 层已按列宽截断，
 * 不会出现「IGNORE 吞掉超长错误」的静默丢数据。
 */
@Mapper
public interface VisitLogMapper extends BaseMapper<VisitLog> {

    @Insert("INSERT IGNORE INTO visit_log (visitor_id, session_id, is_guest, entry_path, source, "
            + "ip, ua, entered_at, last_seen_at, active_seconds, ended) "
            + "VALUES (#{visitorId}, #{sessionId}, #{isGuest}, #{entryPath}, #{source}, "
            + "#{ip}, #{ua}, #{enteredAt}, #{lastSeenAt}, #{activeSeconds}, 0)")
    int insertIgnore(VisitLog log);

    /**
     * 心跳只推进、不回退停留时长。
     *
     * <p>is_guest 用 GREATEST：游客点了「游客登录」后只能从 0 变 1，
     * 不会因为某次心跳没带标记又变回去。ended 用最后一次上报值，
     * 这样页面从 bfcache 恢复后重新计时的会话不会被误判为已结束。
     */
    @Update("UPDATE visit_log SET last_seen_at = #{lastSeenAt}, "
            + "active_seconds = GREATEST(active_seconds, #{activeSeconds}), "
            + "is_guest = GREATEST(is_guest, #{isGuest}), ended = #{ended} "
            + "WHERE session_id = #{sessionId}")
    int updateProgress(@Param("sessionId") String sessionId,
            @Param("lastSeenAt") LocalDateTime lastSeenAt,
            @Param("activeSeconds") int activeSeconds,
            @Param("isGuest") int isGuest,
            @Param("ended") int ended);

    @Select("SELECT COUNT(*) FROM visit_log WHERE entry_path = '/guest' "
            + "AND entered_at >= #{start} AND entered_at < #{end}")
    long countScanSessions(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(DISTINCT visitor_id) FROM visit_log WHERE entry_path = '/guest' "
            + "AND entered_at >= #{start} AND entered_at < #{end}")
    long countScanVisitors(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM visit_log WHERE is_guest = 1 "
            + "AND entered_at >= #{start} AND entered_at < #{end}")
    long countGuestSessions(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 已结算的游客会话时长。
     *
     * <p>「已结算」= 收到结束上报，或最后心跳已超过 staleBefore。
     * 仍在页面里的会话不参与平均/中位，避免刚开始几秒就把均值拉低。
     */
    @Select("SELECT active_seconds FROM visit_log WHERE is_guest = 1 "
            + "AND entered_at >= #{start} AND entered_at < #{end} "
            + "AND (ended = 1 OR last_seen_at < #{staleBefore})")
    List<Integer> selectSettledSeconds(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 来源分组（2026-11-14 改）。
     *
     * <p>不再把空来源过滤掉：未带 from 标签的扫码是实打实的人，
     * 排名图里漏掉这一组，各来源的占比就会凭空放大。
     * 空来源返回 null，前端显示为「未标注」。
     */
    @Select("SELECT source, COUNT(*) AS sessions FROM visit_log "
            + "WHERE is_guest = 1 AND entered_at >= #{start} AND entered_at < #{end} "
            + "GROUP BY source ORDER BY sessions DESC LIMIT 6")
    List<VisitSourceCountVO> selectSources(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 近 7 日按天聚合（2026-11-14 加）。
     *
     * <p>一次 GROUP BY 取回所有天，避免前端逐日发 7 个请求；
     * 没有数据的日子不返回，由 service 补零成连续 7 天。
     */
    @Select("SELECT DATE_FORMAT(entered_at, '%Y-%m-%d') AS date, "
            + "COUNT(DISTINCT CASE WHEN entry_path = '/guest' THEN visitor_id END) AS scan_visitors, "
            + "SUM(CASE WHEN entry_path = '/guest' THEN 1 ELSE 0 END) AS scan_sessions, "
            + "SUM(CASE WHEN is_guest = 1 THEN 1 ELSE 0 END) AS guest_sessions "
            + "FROM visit_log WHERE entered_at >= #{start} AND entered_at < #{end} "
            + "GROUP BY DATE_FORMAT(entered_at, '%Y-%m-%d') ORDER BY date")
    List<VisitDayStatVO> selectDailyTrend(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Delete("DELETE FROM visit_log WHERE entered_at < #{before} LIMIT #{limit}")
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}