package com.campus.social.service.impl;

import com.campus.social.entity.Notify;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AiReviewTrigger;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 帖子写路径的横切能力：事务后 AI 机审触发 + 站内通知下发。
 *
 * <p><b>为什么单独成类</b>：这两件事都不是「发帖」或「编辑」独有的，
 * 评论、搭子卡片、举报处置将来都会用到。留在 PostWriteService 里会让每个新入口
 * 都重复一遍「注册 afterCompletion +判断提交状态」这段容易写错的样板。
 *
 * <p><b>关键约束：AI 机审必须在事务提交后才触发</b>。LLM 往返是秒级，
 * 放在写事务里会把行锁持有时间拉长到秒级，并发一高就互相等待；
 * 更糟的是事务回滚后机审已经跑完，一张不存在的帖产生了审核结论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostNotifySupport {

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;
    private final AiReviewTrigger aiReviewTrigger;

    /**
     * 事务提交后触发 AI 机审；无事务时直接调。
     *
     * <p>回滚不触发：内容没落库，让 LLM 去审一条不存在的帖子是纯浪费。
     */
    public void scheduleAiReviewAfterCommit(long postId, String content, String mediaJson) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        triggerAiReview(postId, content, mediaJson);
                    }
                }
            });
        } else {
            triggerAiReview(postId, content, mediaJson);
        }
    }

    private void triggerAiReview(long postId, String content, String mediaJson) {
        try {
            aiReviewTrigger.moderateAndApply(postId, content, mediaJson);
        } catch (Exception e) {
            // 机审失败不影响发帖：帖已入库，人工审核队列里还有这条。
            // 吞掉异常是刻意的——让机审异常冒到接口层会把「发帖成功」变成「发帖失败」
            log.warn("ai review trigger fail: post={}, err={}", postId, e.getMessage());
        }
    }

    /**
     * 下发站内通知 + 实时推送。
     *
     * <p>未读数与通知行必须在同一逻辑单元内：只写 notify 不改未读数，
     * 用户永远看不到红点；只改未读数不写 notify，点了进去是空的。
     */
    public void pushNotify(long userId, int type, long targetId, long actorId, Long postId) {
        Notify n = new Notify();
        n.setUserId(userId);
        n.setType(type);
        n.setTargetId(targetId);
        n.setPostId(postId);
        n.setActorId(actorId);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(userId, 1);
        // WS 实时推（离线落表，上线后 REST 拉）
        wsPush.pushNotify(userId, n.getId(), type, targetId, actorId);
    }
}
