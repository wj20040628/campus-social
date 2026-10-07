package com.campus.social.service.impl;

import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.mapper.*;
import com.campus.social.service.AiReviewTrigger;
import com.campus.social.service.AuditService;
import com.campus.social.service.LlmModerationService;
import com.campus.social.service.ModerationVerdict;
import com.campus.social.service.PostService;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 机审触发器实现:
 *  - moderateAndApply 由 PostServiceImpl 在 create()/edit() 的【事务提交后】调用
 *  - @Async(llmExecutor):LLM 往返不锁任何写事务
 *  - 结论落地(需短事务):写 review_task.ai_* + 自动流转 post.status + 推作者通知
 *
 * verdict 语义:
 *  - approve → post.status=1 + review_task.status=1 + 写扩散 + 推作者 type=7
 *  - reject  → post.status=2 + review_task.status=2 + 推作者 type=8
 *  - manual  → review_task 保持 status=0(留给人审),仅填 ai_verdict/ai_reason,推作者 type=6(已在 create 时发过,这里不重发)
 *
 * 人审可覆盖:AI 只是预审加速,人审 approve/reject 仍走 ReviewServiceImpl(幂等 status!=0 拒绝),
 * 所以 AI 自动流转后,审核台对该 task 仍可再处理(若已被 AI 定 status!=0 则幂等拒绝,属正常)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewTriggerImpl implements AiReviewTrigger {

    private final LlmModerationService llm;
    private final PostMapper postMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    /** PostService 与 PostServiceImpl 互相依赖(后者注入 AiReviewTrigger,后者又需前者 fanoutOnApproved),
     *  用 ObjectProvider 延迟解析打破构造期环(拿到的是已装配完成的 bean) */
    private final ObjectProvider<PostService> postServiceProvider;
    private final NotifyMapper notifyMapper;
    private final com.campus.social.mapper.UserMapper userMapper;
    private final WsPushService wsPush;
    private final AuditService auditService;

    @Override
    @Async("llmExecutor")
    public void moderateAndApply(long postId, String content, String mediaJson) {
        try {
            ModerationVerdict v = llm.moderate(content, mediaJson);
            applyVerdict(postId, v);
        } catch (Exception e) {
            log.error("ai review trigger fail: post={}, err={}", postId, e.getMessage());
            // 兜底:任何异常都转人工(保证链路不崩,人工台能看到)
            try {
                applyVerdict(postId, ModerationVerdict.manual("机审触发异常,转人工: " + e.getMessage(), null));
            } catch (Exception ignored) {
            }
        }
    }

    /** 短事务内落地:写 review_task.ai_* + 自动流转 post.status + 通知作者 */
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
            post.setStatus(1);
            postMapper.updateById(post);
            postService().fanoutOnApproved(postId);
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
            post.setStatus(2);
            postMapper.updateById(post);
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
    private PostService postService() {
        return postServiceProvider.getObject();
    }

    private void pushReviewNotify(long authorId, int type, Post post, String source) {
        Notify n = new Notify();
        n.setUserId(authorId);
        n.setType(type);
        n.setTargetId(post.getId());
        n.setPostId(post.getId());
        n.setActorId(null); // AI 系统判定,无具体操作人
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(authorId, 1);
        wsPush.pushNotify(authorId, n.getId(), type, post.getId(), 0L);
        log.info("ai review notify: post={}, type={}, author={}, source={}", post.getId(), type, authorId, source);
    }
}
