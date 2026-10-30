package com.campus.social.vo;

import lombok.Data;

/**
 * 他人主页的公开信誉徽章（GET /user/{id}/trust-badge，2026-10-25）。
 *
 * <p><b>只给「是」不给「否」的原因</b>：受信为 false 的原因可能是账号太新、
 * 信誉分不足、或有前科——把 false 下发展示「信誉差」等于把前科公开处刑，
 * 还会让新用户被误读。所以徽章组件只渲染 true；
 * false 的响应本身也是模糊的（看不出哪条不满足），无信息泄露。
 *
 * <p>判定与信任闸门/设置页信誉卡同源（ReviewTrustService.status）——
 * 三处显示必须严格一致，否则卡的可信度崩塌。
 */
@Data
public class TrustBadgeVO {

    /** 是否受信（注册满 trust-min-days + 信誉分达信任线 + 无成立举报 + 无封禁史） */
    private Boolean trusted;
}
