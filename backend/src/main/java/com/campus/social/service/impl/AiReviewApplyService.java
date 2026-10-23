package com.campus.social.service.impl;

import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.mapper.*;
import com.campus.social.service.AuditService;
import com.campus.social.service.ModerationVerdict;
import com.campus.social.service.PostService;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 机审结论落地(短事务)。
 *
 * <p><b>为什么是独立 Bean(2026-10-23 修,审查 P0-6)</b>:原先 applyVerdict 挂在
 * AiReviewTriggerImpl 里被同类 moderateAndApply 隐式 this. 调用 —— Spring 事务基于
 * 代理,同类自调用不过代理,{@code @Transactional} 完全失效,四次写入
 * (review_task/post/fanout/notify)各自独立提交,任何一步失败留下不可恢复的半截状态。
 * 拆到独立 Bean 后经代理调用,事务真正生效。
 *
 * <p>顺带修同一处发现的 {@code @Transactional} 失效连锁问题(P0-7 同族):
 * 落地前先用条件 UPDATE 占坑(WHERE status=0),占不到说明已被人审/AI 并发处理,直接放弃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewApplyService {

    private final PostMapper postMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final NotifyMapper notifyMapper;
    private final com.campus.social.mapper.UserMapper userMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;
    private final AuditService auditService;
    private final PostService postService;

    /**
     * 短事务内落地:写 review_task.ai_* + 自动流转 post.status + 通知作者。
     * 只在 task 仍为待审(status=0)时生效,幂等。
     */
    @Transactional
    public void applyVerdict(long postId, ModerationVerdict v) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            log.warn("ai review: post={} gone, skip", postId);
            return;
        }

        ReviewTask task = reviewTaskMapper.selectByIdForPost(postId);
        if (task == null) {
            // 没进审核队列(直接通过的帖)不该被 AI 判定,忽略
            log.info("ai review: post={} has no review_task, skip", postId);
            return;
        }

        // 只在待审(status=0)时落地;已被人审处理过则跳过(人审优先,AI 不覆盖已人工结论)
        if (task.getStatus() != null && task.getStatus() != 0) {
            log.info("ai review: post={} already processed (status={}), skip", postId, task.getStatus());
            return;
        }

        // 记录 AI 结论 + 时间
        task.setAiVerdict(v.getVerdict());
        task.setAiReason(v.getReason());
        task.setAiReviewedAt(java.time.LocalDateTime.now());

        if (v.isApprove()) {
            // 自动通过:post.status 0→1 + task.status=1 + 写扩散
            task.setStatus(1);
            task.setReviewerId(null); // AI 判定,非人工
            reviewTaskMapper.updateById(task);
            // 只更新 status 列,不整行回写(审查 P1-14):快照里的 like/comment 计数
            // 可能在 select 之后已被并发点赞改过,updateById 全字段回写会抹掉别人的增量
            postMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, postId)
                    .set(Post::getStatus, 1)
                    .set(Post::getUpdateTime, java.time.LocalDateTime.now()));
            postService.fanoutOnApproved(postId);
            pushReviewNotify(post.getAuthorId(), 7, post, "AI 机审通过");
            auditService.recordSystem("review_ai_approve", "post", postId,
                    "AI 机审通过: " + v.getReason());
            log.info("ai review: post={} auto-APPROVED, reason={}", postId, v.getReason());

        } else if (v.isReject()) {
            // 自动驳回:post.status 0→2 + task.status=2 + reason 记 AI 理由
            task.setStatus(2);
            task.setReason(v.getReason());
            task.setReviewerId(null);
            reviewTaskMapper.updateById(task);
            postMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, postId)
                    .set(Post::getStatus, 2)
                    .set(Post::getUpdateTime, java.time.LocalDateTime.now()));
            pushReviewNotify(post.getAuthorId(), 8, post, "AI 机审驳回");
            auditService.recordSystem("review_ai_reject", "post", postId,
                    "AI 机审驳回: " + v.getReason());
            log.info("ai review: post={} auto-REJECTED, reason={}", postId, v.getReason());

        } else {
            // manual:保持 task.status=0,仅落 AI 预判供人审参考(作者已在 create 时收 type=6,不重发)
            reviewTaskMapper.updateById(task);
            auditService.recordSystem("review_ai_manual", "post", postId,
                    "AI 机审转人工: " + v.getReason());
            log.info("ai review: post={} defer to HUMAN, aiReason={}", postId, v.getReason());
        }
    }

    /** 审核结果通知作者:type=7 通过 / type=8 驳回(与 ReviewServiceImpl 同型) */
    private void pushReviewNotify(long authorId, int type, Post post, String source) {
        Notify n = new Notify();
        n.setUserId(authorId);
        n.setType(type);
        n.setTargetId(post.getId());
        n.setPostId(post.getId());
        n.setActorId(null); // AI 系统判定,无具体操作人(actor_id 列已改可空,审查 P0-5)
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(authorId, 1);
        wsAfterCommit.run(() -> wsPush.pushNotify(authorId, n.getId(), type, post.getId(), 0L));
        log.info("ai review notify: post={}, type={}, author={}, source={}", post.getId(), type, authorId, source);
    }
}
