package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.FeedItem;
import com.campus.social.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeedItemMapper extends BaseMapper<FeedItem> {

    /**
     * 幂等写收件箱(2026-10-23 加,审查 P0-7):依赖 uk_fan_post(fan_id, post_id) 唯一键,
     * 重复扩散(审核并发通过/发帖与补扩散撞车)静默跳过而不是插出重复行。
     * 该唯一键进 schema.sql 基线;存量库先跑 sql/fix_feed_item_unique.sql。
     */
    @Insert("INSERT IGNORE INTO feed_item (fan_id, author_id, post_id) "
            + "VALUES (#{fanId}, #{authorId}, #{postId})")
    int insertIgnore(@Param("fanId") long fanId, @Param("authorId") long authorId, @Param("postId") long postId);

    /** 物理删除某帖子扩散出的所有 feed_item */
    @Delete("DELETE FROM feed_item WHERE post_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /**
     * 关注 Feed:拉某粉丝收件箱(feed_item)里已投递的帖子,
     * JOIN post 取内容,只留 已通过/未删 的。走 idx_fan_time(fan_id, id DESC)。
     * 游标 lastId = 上一页末条 feed_item.id,lastId==null 表示第一页(取最大的 N 条)。
     */
    @Select("<script>" +
            "SELECT p.* FROM feed_item f JOIN post p ON p.id = f.post_id " +
            "WHERE f.fan_id = #{fanId} AND f.is_deleted = 0 AND p.status = 1 AND p.is_deleted = 0 " +
            /*
             * 拉黑作者过滤(2026-10-24 补,与全量流同口径):拉黑会双向取关,
             * 收件箱里的旧投递不会跟着消失,读侧必须挡——否则"关注流看不到、
             * 拉黑前的旧帖却还躺在收件箱里"。双向判定同 buildFeedSql。
             */
            "AND NOT EXISTS (SELECT 1 FROM relation r WHERE r.is_deleted = 0 AND r.type = 3 " +
            "AND ((r.from_user_id = #{fanId} AND r.to_user_id = p.author_id) " +
            "OR (r.from_user_id = p.author_id AND r.to_user_id = #{fanId}))) " +
            "<if test='lastId != null'> AND f.id &lt; #{lastId}</if> " +
            "ORDER BY f.id DESC LIMIT #{limit}" +
            "</script>")
    List<Post> selectFeedForFan(@Param("fanId") long fanId, @Param("lastId") Long lastId, @Param("limit") int limit);
}
