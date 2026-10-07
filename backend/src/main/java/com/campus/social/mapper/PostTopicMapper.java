package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.PostTopic;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PostTopicMapper extends BaseMapper<PostTopic> {

    /** 查某帖子关联的话题 id 列表 */
    @Select("SELECT topic_id FROM post_topic WHERE post_id = #{postId} AND is_deleted = 0")
    List<Long> topicIdsByPost(@Param("postId") long postId);

    /** 软删某帖子的全部话题关联(编辑帖"删旧"用,保留审计;is_deleted 统一逻辑删) */
    @Update("UPDATE post_topic SET is_deleted = 1, update_time = NOW() WHERE post_id = #{postId} AND is_deleted = 0")
    int softDeleteByPost(@Param("postId") long postId);

    /** 批量查多个帖子各自的话题 id(Feed 回填用,避免逐帖查) */
    @Select("<script>" +
            "SELECT post_id, topic_id FROM post_topic WHERE is_deleted = 0 AND post_id IN " +
            "<foreach collection='postIds' item='pid' open='(' close=')' separator=','>#{pid}</foreach>" +
            "</script>")
    List<PostTopic> listByPostIds(@Param("postIds") List<Long> postIds);

    /** 物理删除某帖子的全部话题关联(删帖级联用) */
    @Delete("DELETE FROM post_topic WHERE post_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /** 查某话题下所有帖子 id(话题落地页用,走 idx_topic) */
    @Select("SELECT post_id FROM post_topic WHERE topic_id = #{topicId} AND is_deleted = 0")
    List<Long> postIdsByTopic(@Param("topicId") long topicId);
}
