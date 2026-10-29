package com.campus.social.service.impl;

import com.campus.social.entity.Notify;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AiReviewTrigger;
import com.campus.social.websocket.WsPushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;

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

    /** 解析 notify_pref 用,无状态可复用 */
    private static final ObjectMapper OM = new ObjectMapper();

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
        // 通知偏好:被用户关闭的类型不投递(免打扰)。反馈回复 type=11 走 pushFeedbackReply,不在此受限。
        Set<Integer> disabled = parseDisabled(userMapper.selectNotifyPrefById(userId));
        if (disabled.contains(type)) {
            log.debug("notify skipped by pref: userId={}, type={}", userId, type);
            return;
        }
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

    /**
     * 系统处置通知（2026-10-28）：举报结果、封禁、下架、解封共用。
     *
     * <p>actor 恒为 null——处置是平台动作，用户看到「管理员 #3 封了你」既无意义
     * 也不安全（反馈回复的匿名口径同此）。正文放 content，前端整段展示。
     *
     * <p>targetId 按 notify.target_id NOT NULL 约束兜底为 0；账号类通知
     * （封禁/解封）本来就没有可指向的实体。
     */
    public void pushSystemNotify(long userId, int type, Long targetId, Long postId, String content) {
        Set<Integer> disabled = parseDisabled(userMapper.selectNotifyPrefById(userId));
        if (disabled.contains(type)) {
            log.debug("system notify skipped by pref: userId={}, type={}", userId, type);
            return;
        }
        Notify n = new Notify();
        n.setUserId(userId);
        n.setType(type);
        n.setTargetId(targetId == null ? 0L : targetId);
        n.setPostId(postId);
        n.setActorId(null);
        n.setContent(content);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(userId, 1);
        wsPush.pushNotify(userId, n.getId(), type, n.getTargetId(), 0L);
    }

    /**
     * 认证审核结果通知（2026-10-28）。
     *
     * <p>为什么单独一个方法：学号核验既不是内容也不是文件，它的「跳转目标」
     * 是编辑资料页而不是某个实体详情，所以 targetId 没有可指向的行——
     * notify.target_id 是 NOT NULL，这里固定写 0（前端不读它）。
     *
     * <p>actor 留空：通过/驳回是平台动作，让用户看到「管理员 #3 驳回了你」
     * 既无意义也不安全。驳回理由走 content，与反馈回复同一口径。
     */
    public void pushVerifyResult(long userId, int type, String content) {
        Set<Integer> disabled = parseDisabled(userMapper.selectNotifyPrefById(userId));
        if (disabled.contains(type)) {
            log.debug("verify notify skipped by pref: userId={}, type={}", userId, type);
            return;
        }
        Notify n = new Notify();
        n.setUserId(userId);
        n.setType(type);
        n.setTargetId(0L);
        n.setPostId(null);
        n.setActorId(null);
        n.setContent(content);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(userId, 1);
        wsPush.pushNotify(userId, n.getId(), type, 0L, 0L);
    }

    /** 解析 notify_pref 列里的 disabled 类型集合;NULL/空/非法都视为全开 */
    private Set<Integer> parseDisabled(String pref) {
        Set<Integer> disabled = new HashSet<>();
        if (pref == null || pref.isBlank()) return disabled;
        try {
            JsonNode node = OM.readTree(pref);
            JsonNode arr = node.get("disabled");
            if (arr != null && arr.isArray()) {
                arr.forEach(e -> disabled.add(e.asInt()));
            }
        } catch (Exception e) {
            // 坏数据不阻塞通知,当作全开
        }
        return disabled;
    }

    /**
     * 反馈回复通知：管理员处置反馈时把处理说明作为回复推给提交者。
     *
     * <p>与 {@link #pushNotify} 同一条路径（写 notify + 未读数 +1 + WS 实时推），
     * 唯一区别是带 {@code content} 自由文本——原话存库，前端直接渲染，
     * 不必再为「反馈回复」硬造一套 type→文案映射。
     *
     * <p>只在该发的时候发：调用方（FeedbackServiceImpl.handle）已用乐观锁挡掉
     * 重复处置，这里不会收到二次调用，故不会重发。
     *
     * @param toUserId 反馈提交者（接收方）
     * @param feedbackId 反馈 id（存 targetId，点通知时可作为线索）
     * @param adminId 处置管理员（actorId）
     * @param content 回复原文（即 handleNote）
     */
    public void pushFeedbackReply(long toUserId, long feedbackId, long adminId, String content) {
        Notify n = new Notify();
        n.setUserId(toUserId);
        n.setType(11);
        n.setTargetId(feedbackId);
        n.setPostId(null);
        n.setActorId(adminId);
        n.setContent(content);
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(toUserId, 1);
        wsPush.pushNotify(toUserId, n.getId(), 11, feedbackId, adminId);
    }
}
