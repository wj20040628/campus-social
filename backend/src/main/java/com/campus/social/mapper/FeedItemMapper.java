package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.FeedItem;
import com.campus.social.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeedItemMapper extends BaseMapper<FeedItem> {

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
            "<if test='lastId != null'> AND f.id &lt; #{lastId}</if> " +
            "ORDER BY f.id DESC LIMIT #{limit}" +
            "</script>")
    List<Post> selectFeedForFan(@Param("fanId") long fanId, @Param("lastId") Long lastId, @Param("limit") int limit);
}
