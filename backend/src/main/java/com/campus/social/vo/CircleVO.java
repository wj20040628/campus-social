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
}
