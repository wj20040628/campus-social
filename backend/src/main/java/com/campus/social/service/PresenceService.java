package com.campus.social.service;

import com.campus.social.vo.PresenceStatsVO;
import com.campus.social.vo.PresenceStatusVO;

import java.util.List;

/**
 * 在线状态（2026-11-13）。
 *
 * <p>在线判定来自活跃 WebSocket 会话；隐身用户仍算在线，
 * 但对外批量查询与公共统计不返回他，管理总览单独统计全部在线。
 */
public interface PresenceService {

    /** 查询这批 userId 中「在线且未隐身」的用户；他人看不到隐身用户。 */
    PresenceStatusVO status(List<Long> userIds);

    /** 首页公共统计：在册用户数 + 在线且未隐身的用户数。 */
    PresenceStatsVO stats();

    /** 设置/取消隐身，返回设置后的值。 */
    boolean setHidden(long userId, boolean hidden);

    /** 管理总览用：全部在线用户数（含隐身）。 */
    int onlineAllCount();
}
