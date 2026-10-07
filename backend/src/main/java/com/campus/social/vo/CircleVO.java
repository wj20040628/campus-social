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
}
