package com.campus.social.vo;

import lombok.Data;

/**
 * 发布信誉状态（GET /user/trust-status，设置页「账号与安全」的信誉卡）。
 *
 * <p><b>给清单不给公式细节</b>：这是给用户看的自省视图，不是审核规则的
 * 完整披露——呈现为账龄/信誉/违规记录三条，不暴露「变体命中恒送人审」
 * 之类的检测细节，那是要留给恶意用户的盲区。校园信息只作为资料标记展示。
 */
@Data
public class UserTrustStatusVO {

    /** 是否受信（四条件全满足）：命中 level-1 词的帖子可直接发布（展示时打码） */
    private Boolean trusted;

    /** 校园信息是否已登记（verify_status=1；仅资料展示，不参与权限） */
    private Boolean verified;

    /** 账号已注册天数（create_time 为空按 0 算） */
    private Integer accountAgeDays;

    /** 受信要求的账龄下限（天），与 campus.review.trust-min-days 同值 */
    private Integer trustMinDays;

    /** 是否有违规记录（近 credit-decay-days 天内存在成立举报或封禁史） */
    private Boolean hasBadRecord;

    /**
     * 信誉分(2026-10-25 v2,2026-10-27 纳入文件):基准 60
     * + 产出加分(过审帖/集市/文件 ×1,总封顶 40;文件来源另封顶 10)
     * − 成立举报×20 − 封禁×40,clamp 0..100,30 天窗口,动态计算不落库。
     * 受信要求 ≥60(即基准线):扣分掉线,产出可补回。
     */
    private Integer creditScore;

    /** 当前已计入的产出加分(未封顶部分另可见于分数;此字段=实际计入值) */
    private Integer creditBonus;

    /** 信誉记录的计算窗口(天),与 campus.review.credit-decay-days 同值 */
    private Integer creditDecayDays;

    /** 可建圈子数上限(在世口径,解散释放;按信誉分 1/2/3,不看校园信息) */
    private Integer circleQuota;

    /** 集市同时在投上限(过期/暂停/删除释放;按信誉分 1/2/3,不看校园信息) */
    private Integer mktQuota;

    /** 每日发帖上限(按信誉档位 5/10/15,不看校园信息) */
    private Integer postDailyQuota;
}
