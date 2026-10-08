package com.campus.social.service;

import com.campus.social.vo.ChatSendVO;

/**
 * 搭话护栏（F3 三重护栏的编排层）。
 *
 * <p>抽成独立 service 而不是塞进 {@code ChatServiceImpl.send()}：
 * 后者已有 268 行，塞进护栏必然突破 300 行门禁；且护栏本身是
 * 「顺序敏感」的一组判定，混在消息落库逻辑里会让人看不出执行次序。
 *
 * <p><b>执行顺序不可调换</b>（openapi.yaml:272-278），原则是先便宜后昂贵：
 * 便宜的判定放前面，被拒的请求就越少走 Redis 与 DFA 扫描。
 */
public interface ChatGuardService {

    /**
     * 发送前的全部校验，通过才允许落库。
     *
     * @param me       发送方
     * @param peerId   接收方
     * @param content  原始内容（敏感词过滤用原文，替换后的 * 文本查不出命中词）
     * @return 本次发送是否属于向陌生人发起，以及扣减后的剩余新对象额度
     */
    ChatSendVO check(long me, long peerId, String content);
}