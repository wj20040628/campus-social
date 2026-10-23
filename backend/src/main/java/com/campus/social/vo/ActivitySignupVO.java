package com.campus.social.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * 活动报名者（2026-10-21 新增，供活动详情页的「参加的人」用）。
 *
 * <p><b>为什么要有它</b>：改版前 {@code ActivityVO} 只有 {@code currentCount}
 * 一个数字 —— 用户报了名，不知道同去的是谁，也没法在活动页找到人私信。
 * 而这类活动的核心风险恰恰是「和陌生人线下见面」，知道参与人选直接关系到安全。
 *
 * <p><b>为什么不复用 UserVO</b>：这里只需要「昵称 + 头像 + 是不是我」三样，
 * UserVO 会连带下发认证状态、粉丝数等一堆本页用不到的字段。
 * 走独立 VO 也和 CircleMemberVO 的做法一致 —— 列表项的字段应当由用它的人决定。
 *
 * <p>不含学号、不含年级学院：报名名单是**所有人可见**的，
 * 最小披露原则下只给识别「这个人是谁」所必需的信息。
 */
@Data
@Builder
public class ActivitySignupVO {
    private Long userId;
    private String nickname;
    private String avatarUrl;

    /**
     * 这个人是不是当前查看者自己。
     *
     * 由前端自己比对 userId 也能做到，但要额外拿到「我的 id」并处理未登录场景。
     * 后端算一次更省事，也避免前端在两处各写一遍比对逻辑。
     *
     * 用 {@code @JsonProperty} 显式钉住字段名为 isMe：Lombok 给 `boolean isMe`
     * 生成的 getter 是 `isMe()`，Jackson 据此推出来的字段名是 `me`
     * （实测响应里就是 `"me":true`）。契约里写 isMe、下发却是 me，
     * 前端按契约写就会读到 undefined —— 这类「字段名对不上」在联调时最难查。
     */
    @JsonProperty("isMe")
    private boolean isMe;
}
