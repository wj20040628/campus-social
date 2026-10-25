package com.campus.social.vo;

import lombok.Data;

/**
 * 发布信誉状态（GET /user/trust-status，设置页「账号与安全」的信誉卡）。
 *
 * <p><b>给清单不给公式细节</b>：这是给用户看的自省视图，不是审核规则的
 * 完整披露——四条件在这里呈现为三条清单（认证/账龄/违规记录），
 * 不暴露「变体命中恒送人审」之类的检测细节，那是要留给恶意用户的盲区。
 */
@Data
public class UserTrustStatusVO {

    /** 是否受信（四条件全满足）：命中 level-1 词的帖子可直接发布（展示时打码） */
    private Boolean trusted;

    /** 学号认证是否通过（verify_status=1） */
    private Boolean verified;

    /** 账号已注册天数（create_time 为空按 0 算） */
    private Integer accountAgeDays;

    /** 受信要求的账龄下限（天），与 campus.review.trust-min-days 同值 */
    private Integer trustMinDays;

    /** 是否有违规记录（成立的用户举报或直接封禁史） */
    private Boolean hasBadRecord;
}
