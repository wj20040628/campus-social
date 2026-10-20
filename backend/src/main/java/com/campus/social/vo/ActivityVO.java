package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ActivityVO {
    private Long id;
    private String title;
    private Long circleId;
    /** 创建者用户 id(前端据此显示「取消活动」入口) */
    private Long creatorId;
    private Integer capacity;
    private Integer currentCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 0报名中 1已满 2进行中 3已结束 4已取消 */
    private Integer status;
    private LocalDateTime createTime;
    /** 当前用户是否已报名 */
    private boolean signedUp;
    /** 是否可报名(状态 0/1 且未报名) */
    private boolean canSignup;
}
