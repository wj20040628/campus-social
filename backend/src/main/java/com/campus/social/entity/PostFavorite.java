package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子收藏。
 *
 * <p><b>is_deleted 是保留列，不参与业务</b>：取消收藏必须物理删除。
 * 若走 {@code @TableLogic} 软删，被软删的行仍然占着 uk_fav_user_post 唯一键，
 * 用户取消后再收藏会直接撞 Duplicate entry —— 这个坑 like 表已经踩过一次
 * （见 {@code LikeMapper#hardDelete} 的注释），此处不再重复。
 *
 * <p>保留 {@code @TableLogic} 注解只是为了和全站实体约定一致：
 * 万一将来有人写了软删，至少查询侧会自动过滤掉，不会把脏行读出来。
 */
@Data
@TableName("post_favorite")
public class PostFavorite implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收藏人 */
    private Long userId;

    /** 被收藏的帖子 */
    private Long postId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
