package com.campus.social.vo;

import lombok.Data;

import java.util.List;

/**
 * 管理总览的「游客 / 扫码」一组数字（2026-11-14）。
 *
 * <p>口径：
 * <ul>
 *   <li>扫码 = 入口路径为 /guest 的会话。线下二维码固定落这个路径；
 *       二维码链接带 from 参数时，按来源分组能区分不同批次；</li>
 *   <li>游客会话 = is_guest = 1；</li>
 *   <li>平均 / 中位停留只统计已结算会话（收到结束上报，或最后心跳超过
 *       60 秒），仍在页面里的会话不参与，避免把均值拉低；</li>
 *   <li>短时离开 = 已结算会话中停留不足 10 秒的数量。</li>
 * </ul>
 */
@Data
public class GuestVisitVO {

    private long todayScanVisitors;

    private long todayScanSessions;

    private long todayGuestSessions;

    private long todaySettledSessions;

    private long todayShortSessions;

    private long avgDwellSeconds;

    private long medianDwellSeconds;

    private long yesterdayScanVisitors;

    private long yesterdayScanSessions;

    private long yesterdayGuestSessions;

    /** 今日游客会话按来源分组（最多 6 组，含未带 from 标签的「未标注」） */
    private List<VisitSourceCountVO> sources;

    /** 近 7 日按天聚合，最后一项是今天；无数据的日子补零 */
    private List<VisitDayStatVO> trend;
}