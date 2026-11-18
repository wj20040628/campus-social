package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 首页公共统计。
 *
 * <p>users = 在册用户数（status != 2）；online = 在线且未隐身的用户数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresenceStatsVO {
    private long users;
    private long online;
}
