package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 两个用户之间当前的关系状态
 * 用于个人主页/详情页展示"已关注 / 互关 / 黑名单"等按钮
 */
@Data
@Builder
public class RelationStatusVO {
    /** 我是否关注了对方 (relation: me→other, type=1) */
    private boolean following;
    /** 对方是否关注了我 (relation: other→me, type=1) */
    private boolean followedBy;
    /** 是否互为好友 (双向 type=2) */
    private boolean friend;
    /** 我是否把对方拉黑 (me→other, type=3) */
    private boolean blacklisted;
}
