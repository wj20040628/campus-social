package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /** 点赞/取消赞:事务内原子 ±1 */
    @Update("UPDATE post SET like_count = like_count + #{delta}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    @Update("UPDATE post SET comment_count = comment_count + #{delta}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 浏览量:进详情原子 +1 */
    @Update("UPDATE post SET view_count = view_count + 1, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustViewCount(@Param("postId") Long postId);

    @Update("UPDATE post SET hot_score = #{hotScore}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int updateHotScore(@Param("postId") Long postId, @Param("hotScore") double hotScore);

    /** 对账:重算 like_count(真实点赞数)+ hot_score(按热度公式) */
    @Update("UPDATE post p SET " +
            "  like_count = (SELECT COUNT(*) FROM `like` l WHERE l.target_type = 1 AND l.target_id = p.id AND l.is_deleted = 0), " +
            "  hot_score = (p.like_count + 2 * p.comment_count) / " +
            "             POWER(GREATEST(1, TIMESTAMPDIFF(HOUR, p.create_time, NOW()) + 2), 1.5), " +
            "  update_time = NOW() " +
            "WHERE p.status = 1 AND p.is_deleted = 0")
    int reconcileHotAndLikes();

    /** 对账:重算 comment_count(真实评论数) */
    @Update("UPDATE post p SET " +
            "  comment_count = (SELECT COUNT(*) FROM comment c WHERE c.post_id = p.id AND c.is_deleted = 0 AND c.status = 1), " +
            "  update_time = NOW() " +
            "WHERE p.is_deleted = 0")
    int reconcileComments();

    /** 圈内置顶:置单帖 is_top(0/1) */
    @Update("UPDATE post SET is_top = #{isTop}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int setIsTop(@Param("postId") long postId, @Param("isTop") int isTop);

    /** 同圈其它置顶归 0(保证圈内同时只有 1 个置顶;传 keepPostId 跳过自己) */
    @Update("UPDATE post SET is_top = 0, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND is_top = 1 AND id <> #{keepPostId} AND is_deleted = 0")
    int clearTopInCircle(@Param("circleId") long circleId, @Param("keepPostId") long keepPostId);

    /** 圈管理下架:status=2(软,作者可自查),非物理删 */
    @Update("UPDATE post SET status = 2, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int takedown(@Param("postId") long postId);

    /** 移帖:改归属圈(toCircleId 为 null=移回广场) */
    @Update("UPDATE post SET circle_id = #{toCircleId}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int moveCircle(@Param("postId") long postId, @Param("toCircleId") Long toCircleId);

    /** 物理删除帖子(绕过 @TableLogic),级联数据在 service 里清 */
    @Delete("DELETE FROM post WHERE id = #{postId}")
    int hardDelete(@Param("postId") Long postId);

    /**
     * 某用户发的动态数(他人主页头部「动态」数字用)。
     * 口径与 PostFeedQueryService.myPosts 一致:status IN (0,1,2) 且未删。
     * 现查 post 表而非依赖冗余计数列 —— 列漂移时列表是对的、头部也会跟着对。
     */
    @Select("SELECT COUNT(*) FROM post WHERE author_id = #{authorId} AND status IN (0,1,2) AND is_deleted = 0")
    int countByAuthor(@Param("authorId") Long authorId);

    /**
     * 帖子全文搜索(MATCH/AGAINST,中文走 ngram 分词器)。
     * keyword 在 service 层做"引号等特殊字符过滤"后传入(MyBatis #{} 参数化,无注入)。
     * 仅查已通过且未删的帖子;返回按相关度(默认) + id 倒序。
     */
    @Select("SELECT p.* FROM post p WHERE p.status = 1 AND p.is_deleted = 0 " +
            "AND MATCH(p.content) AGAINST (#{keyword} IN NATURAL LANGUAGE MODE) " +
            "ORDER BY p.id DESC LIMIT #{limit}")
    List<Post> searchByFullText(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * Feed 自定义查询(支持按 like/comment/view 排序 + JOIN 话题过滤)。
     * sql 由 service 层拼接,排序列与话题 id 走白名单/查表校验,用户输入不直接进 sql。
     */
    @Select("${sql}")
    List<Post> selectFeedBySql(@Param("sql") String sql);
}
