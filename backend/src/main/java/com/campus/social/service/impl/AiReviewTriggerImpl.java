package com.campus.social.service.impl;

import com.campus.social.service.AiReviewTrigger;
import com.campus.social.service.LlmModerationService;
import com.campus.social.service.ModerationVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AI 机审触发器实现:
 *  - moderateAndApply 由 PostServiceImpl 在 create()/edit() 的【事务提交后】调用
 *  - @Async(llmExecutor):LLM 往返不锁任何写事务
 *  - 结论落地(需短事务):委托独立 Bean {@link AiReviewApplyService}(2026-10-23 拆出,
 *    审查 P0-6:原先同类 this. 调用让 @Transactional 完全失效)
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
    /**
     * 结论落地 Bean:必须经代理调用事务才生效,所以注入的是独立 Bean 而非本类方法。
     * ObjectProvider 打破 PostServiceImpl ↔ 本类 ↔ PostService 的构造期环。
     */
    private final ObjectProvider<AiReviewApplyService> applyService;

    @Override
    @Async("llmExecutor")
    public void moderateAndApply(long postId, String content, String mediaJson) {
        try {
            ModerationVerdict v = llm.moderate(content, mediaJson);
            applyService.getObject().applyVerdict(postId, v);
        } catch (Exception e) {
            log.error("ai review trigger fail: post={}, err={}", postId, e.getMessage());
            // 兜底:任何异常都转人工(保证链路不崩,人工台能看到)
            try {
                applyService.getObject().applyVerdict(postId,
                        ModerationVerdict.manual("机审触发异常,转人工: " + e.getMessage(), null));
            } catch (Exception ignored) {
            }
        }
    }
}
