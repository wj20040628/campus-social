package com.campus.social.websocket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把 WS 推送延迟到「数据库事务提交之后」执行,避免回滚产生幽灵消息。
 *
 * 背景:多个 service 方法标了 {@code @Transactional},方法体内既写库又
 * {@code wsPush.push*}。若推送发生在提交前,一旦事务回滚(如后续校验失败、
 * DB 异常),消息已推给在线客户端却不在库中,产生"幽灵消息"。
 *
 * 用法:
 * <pre>{@code
 *   wsAfterCommit.run(() -> wsPush.pushChat(receiver, convId, msgId, content, me));
 * }</pre>
 *
 * 语义:
 * <ul>
 *   <li>当前线程有活动事务 → 注册 {@code afterCommit} 回调,提交成功才推送;
 *       回滚则回调不触发,天然丢弃(符合"没写进库就别推")。</li>
 *   <li>无活动事务(非事务方法/测试)→ 立即执行,保持原行为,不破坏现有调用方。</li>
 * </ul>
 */
@Component
public class WsAfterCommit {

    /**
     * 有活动事务则延迟到提交后执行;无事务立即执行。
     *
     * @param task 要执行的推送动作(内部可自行捕获异常并记日志)
     */
    public void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
