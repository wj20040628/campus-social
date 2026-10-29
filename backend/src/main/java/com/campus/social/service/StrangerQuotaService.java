package com.campus.social.service;

import com.campus.social.vo.StrangerQuotaVO;

/**
 * 陌生人搭话配额（F3 护栏之一）。
 *
 * <p>用 Redis ZSET 滑动窗口实现，<b>不建表</b>：频控数据没有审计价值，
 * 为它建表等于给一个纯计数逻辑加上持久化与清理两份负担。
 *
 * <p>实现纪律：所有记账一律走 {@code util/RedisLua} 的滑动窗口脚本。
 * Redis 5.0.14.1 没有 GETDEL 等 6.2 新指令，本项目已因此炸过一次（SPEC §11 第 1 条）。
 */
public interface StrangerQuotaService {

    /**
     * 对同一陌生人发消息前的配额扣减。
     *
     * <p>只限制「对同一个人」的频率，不限制对象数量：对象数量不是风险，
     * 对单个对象的骚扰频率才是。对方关注/回复后由调用方跳过本方法。
     *
     * @return 本次发送后的剩余条数（供前端刷新会话内提示）
     * @throws com.campus.social.exception.BusinessException 配额用尽时抛 429
     */
    int consumePerPeer(long userId, long peerId);

    /** 额度快照（不消耗额度，供前端展示会话内规则与剩余条数） */
    StrangerQuotaVO snapshot(long userId, Long peerId);
}
