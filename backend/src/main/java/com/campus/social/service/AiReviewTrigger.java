package com.campus.social.service;

/**
 * AI 机审触发器:在帖子 create()/edit() 的【事务提交后】异步调 LLM 机审,
 * 并把结论回写 review_task + 自动流转 post.status + 推作者通知。
 *
 * 设计(铁律 E1 / B):
 *  - 独立 Service,组合注入,不塞进 PostServiceImpl(职责:审核横切能力)
 *  - 事务后触发:PostServiceImpl 在 @Transactional 方法内注册 afterCompletion,提交后回调本接口,
 *    避免同步调 LLM 把往返时间锁在写事务里
 *  - LLM 走 @Async(llmExecutor),失败/无 key 兜底 manual(转人工),绝不瞎判
 *  - 结论可被人审覆盖:AI 只做预审加速,人审台 approve/reject 仍走 ReviewServiceImpl(幂等)
 */
public interface AiReviewTrigger {

    /**
     * 机审一个送审帖并落地。由 PostServiceImpl 在事务提交后调用。
     * @param postId 帖子 id
     * @param content 帖子原文(LLM 看原貌,非替换后)
     * @param mediaJson 媒体 JSON(可空)
     */
    void moderateAndApply(long postId, String content, String mediaJson);
}
