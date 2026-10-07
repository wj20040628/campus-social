package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Comment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /** 物理删除某帖子的所有评论 */
    @Delete("DELETE FROM `comment` WHERE post_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);
}
