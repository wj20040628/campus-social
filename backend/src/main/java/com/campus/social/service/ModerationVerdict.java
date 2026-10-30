package com.campus.social.service;

/**
 * AI 机审结论。verdict 三态:
 *  - approve:机审判定合规,自动通过(人审可覆盖)
 *  - reject:机审判定违规,自动驳回
 *  - manual:无法判定 / LLM 失败 / 无 key,转人工兜底
 * 任何解析异常、缺字段、非法枚举一律兜底 manual,绝不瞎判(铁律 B)。
 */
public class ModerationVerdict {

    /** 结论:approve / reject / manual */
    private String verdict;
    /** 理由(LLM 返回,可能为空) */
    private String reason;
    /** 置信度 0.0~1.0(可空,LLM 未给则 null) */
    private Double confidence;
    /** LLM 原始返回(调试用,截断存储) */
    private String raw;

    public ModerationVerdict() {}

    public ModerationVerdict(String verdict, String reason, Double confidence, String raw) {
        this.verdict = verdict;
        this.reason = reason;
        this.confidence = confidence;
        this.raw = raw;
    }

    /** 兜底:LLM 失败 / 无 key / 解析异常一律转人工 */
    public static ModerationVerdict manual(String reason, String raw) {
        return new ModerationVerdict("manual", reason, null, raw);
    }

    public static ModerationVerdict approve(String reason, Double confidence, String raw) {
        return new ModerationVerdict("approve", reason, confidence, raw);
    }

    public static ModerationVerdict reject(String reason, Double confidence, String raw) {
        return new ModerationVerdict("reject", reason, confidence, raw);
    }

    public boolean isManual() {
        return "manual".equalsIgnoreCase(verdict);
    }

    public boolean isApprove() {
        return "approve".equalsIgnoreCase(verdict);
    }

    public boolean isReject() {
        return "reject".equalsIgnoreCase(verdict);
    }

    public String getVerdict() {
        return verdict;
    }

    public String getReason() {
        return reason;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getRaw() {
        return raw;
    }
}
