package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("post")
public class Post implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long authorId;

    private String content;

    /** JSON 数组: [{type:"img|video",url:"...",dur:秒}] */
    private String media;

    /** 0审核中 1通过 2下架 3删除 */
    private Integer status;

    private Integer likeCount;
    private Integer commentCount;
    /** 浏览量 */
    private Integer viewCount;

    private Long circleId;

    private Double hotScore;

    /** 圈内置顶 0否1是(圈内列表置顶帖排最前) */
    private Integer isTop;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
