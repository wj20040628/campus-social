package com.campus.social.vo;

import lombok.Data;

/**
 * AI 机审配置状态（GET /admin/review/ai-status，2026-11-08）。
 *
 * <p>只回「配没配、缺什么」，绝不回 API Key 本身。
 * 机审未启用时任务会被写成「未启用 LLM 或缺 key,转人工审核」，
 * 管理员在审核台只能看到这句结论、看不到缺哪个变量；本 VO 就是补这一课。
 */
@Data
public class AiReviewStatusVO {

    /** LLM_ENABLED 是否为 true */
    private boolean enabled;

    /** LLM_API_KEY 是否非空 */
    private boolean keyConfigured;

    /** claude / openai（兼容端点也走 openai 协议） */
    private String provider;

    /** 模型名 */
    private String model;

    /** 是否显式配置了 LLM_BASE_URL；Claude/OpenAI 官方端点可留空 */
    private boolean baseUrlConfigured;

    /** 真正可用的条件：enabled && keyConfigured */
    private boolean ready;

    /** 可直接展示给管理员的中文提示（不含任何密钥） */
    private String hint;
}
