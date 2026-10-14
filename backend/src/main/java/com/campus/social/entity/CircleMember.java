package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("circle_member")
public class CircleMember implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long circleId;
    private Long userId;

    /** 1creator 2admin 3member */
    private Integer role;

    /** 圈内封禁 0正常 1封禁 */
    private Integer banStatus;

    /** 封禁到期(东八区),NULL=长期;到点查询时动态解除 */
    private LocalDateTime banUntil;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
