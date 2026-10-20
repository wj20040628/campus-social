package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("activity")
public class Activity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private Long circleId;
    /** 活动创建者用户 id;迁移前旧数据为 NULL */
    private Long creatorId;

    /** 名额上限,0 不限 */
    private Integer capacity;

    private Integer currentCount;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 0报名中 1已满 2进行中 3已结束 4已取消 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
