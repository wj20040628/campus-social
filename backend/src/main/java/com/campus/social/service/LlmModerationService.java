package com.campus.social.service;

/**
 * AI 内容机审:对送审内容判 通过/驳回/转人工。
 * 实现直接接真实 LLM API(可指 Claude/OpenAI/本地兼容端点);
 * llm.enabled=false 或调用失败一律兜底 manual(转人工),保证本地无 key 也跑通全链路。
 */
public interface LlmModerationService {

    /**
     * @param content   帖子/评论原文(LLM 需看原貌,勿传已替换文案)
     * @param mediaJson 媒体 JSON(可为空;暂不解析图片,只作上下文提示)
     * @return 机审结论;失败/无 key → manual
     */
    ModerationVerdict moderate(String content, String mediaJson);
}
