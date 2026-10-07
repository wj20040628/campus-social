package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Like;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LikeMapper extends BaseMapper<Like> {

    /** 物理删除某帖子的点赞 */
    @Delete("DELETE FROM `like` WHERE target_type = 1 AND target_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /** 物理删除某评论的点赞 */
    @Delete("DELETE FROM `like` WHERE target_type = 2 AND target_id = #{commentId}")
    int hardDeleteByComment(@Param("commentId") long commentId);

    /**
     * 物理删除某人对某目标的点赞(取消点赞用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_like(user_id,target_type,target_id) 继续锁住,
     * 导致再点赞 insert 撞 Duplicate entry。取消点赞应彻底删行。
     */
    @Delete("DELETE FROM `like` WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}")
    int hardDelete(@Param("userId") long userId, @Param("targetType") int targetType, @Param("targetId") long targetId);
}
