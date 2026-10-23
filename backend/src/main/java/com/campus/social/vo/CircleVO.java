package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CircleVO {
    private Long id;
    private String name;
    private Long ownerId;
    private UserVO owner;
    private Integer memberCount;
    private Integer status;
    private LocalDateTime createTime;
    /** 当前用户是否已加入 */
    private boolean joined;
    /** 当前用户在本圈的角色:1圈主 2圈管理 3成员,非成员=0 */
    private int myRole;
    /** 当前用户是否被本圈封禁(仅 role=3 普通成员,且未到期) */
    private boolean myBanned;
    /**
     * 圈内可见帖数(status=1 且未软删)。列表副行「最近动态」用。
     * 为 0 时前端显示「还没有帖子」,不显示时间。
     */
    private Integer postCount;
    /**
     * 最新一帖时间,格式 yyyy-MM-dd HH:mm(空 = 还没人发帖)。
     * 用 String 而不是 LocalDateTime:前端只需要「2 天前有新帖」这种相对时间,
     * 给它一个固定格式的串就够,还能绕开 VO 里的 datetime 序列化口径。
     */
    private String lastPostAt;
}
