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
    /** 收藏数(与 likeCount 同构的冗余列,由 PostServiceImpl 在同事务内 ±1) */
    private Integer favoriteCount;
    /** 分享次数:每次成功保存/分享卡片 +1,不做去重 */
    private Integer shareCount;
    /** 浏览量 */
    private Integer viewCount;

    private Long circleId;

    private Double hotScore;

    /** 圈内置顶 0否1是(圈内列表置顶帖排最前) */
    private Integer isTop;

    /** 树洞匿名帖 0否1是:作者不出现在任何展示出口,管理端(审核/举报/日志)仍可实锤 */
    private Integer isAnonymous;

    /**
     * 推荐流的排序分:当前登录人与作者共同拥有的兴趣标签数。
     * 只在 {@code /post/feed/recommend} 的查询里由 SQL 算出,不落库。
     */
    @TableField(exist = false)
    private Integer sharedTagCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
