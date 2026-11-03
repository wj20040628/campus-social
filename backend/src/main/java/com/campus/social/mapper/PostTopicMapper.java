package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Post;
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

    /**
     * 话题落地页游标分页(2026-11-03):JOIN post 直接下推排序/游标/limit。
     *
     * <p>原先的实现是「postIdsByTopic 全量拉 id → selectBatchIds 全量拉帖子 →
     * 内存里排序/过滤/截断」。数据少时能用,话题热了(几千帖)每翻一页都
     * 把整个话题搬进内存,内存与 CPU 双涨。
     *
     * <p>全 SQL 等价语义:status=1 未删、按 id 倒序、{@code id < lastId} 游标、
     * 多拉一条(limit+1)判 hasMore —— 与综合 Feed 的口径一致。
     * SQL 全为常量 + #{} 占位,无注入面。
     *
     * <p><b>必须包 {@code <script>}</b>(2026-11-03 踩坑实录):注解 @Select 的
     * 动态标签(&lt;if&gt;/&lt;foreach&gt; 等)只在 script 里生效 —— 不包时
     * MyBatis 把 {@code <if ...>} 当<b>纯文本</b>原样拼进 SQL,MySQL 直接
     * 语法错误 500。项目里既有用法(见 listByPostIds)也都包了 script。
     */
    @Select("<script>" +
            "SELECT p.* FROM post p JOIN post_topic pt ON pt.post_id = p.id " +
            "AND pt.topic_id = #{topicId} AND pt.is_deleted = 0 " +
            "WHERE p.status = 1 AND p.is_deleted = 0 " +
            "<if test='lastId != null'>AND p.id &lt; #{lastId} </if>" +
            "ORDER BY p.id DESC LIMIT #{limit}" +
            "</script>")
    List<Post> pagePostsByTopic(@Param("topicId") long topicId,
                                @Param("lastId") Long lastId,
                                @Param("limit") int limit);
}
