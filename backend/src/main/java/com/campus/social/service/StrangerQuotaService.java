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
     * 向新对象发起首条前的配额扣减。
     *
     * @return true=还有额度已记账；false=今日额度用尽
     */
    boolean tryConsumeNewPeer(long userId, long peerId);

    /** 对同一陌生人发消息前的配额扣减 */
    boolean tryConsumePerPeer(long userId, long peerId);

    /** 额度快照（不消耗额度，供前端展示「今日还能搭话 N 人」） */
    StrangerQuotaVO snapshot(long userId, Long peerId);

    /**
     * 陌生人发信的配额判定与记账，两个计数器一并处理。
     *
     * <p>之所以不让调用方自己连着调两次：本 service 的两个计数器必须
     * 「要么都扣成功，要么都不扣」。调用方分开调时，中间任何一步失败都会留下
     * 半扣的状态，而 ZSET 无法回退——member 可能是本次新加的，也可能是
     * 之前就在的，{@code ZREM} 区分不了这两种情况，盲目回退会误删历史记账。
     *
     * @return 剩余新对象额度（供前端刷新「还能搭话 N 人」）
     * @throws com.campus.social.exception.BusinessException 配额用尽时抛 429
     */
    int consumeStrangerQuota(long userId, long peerId);
}
