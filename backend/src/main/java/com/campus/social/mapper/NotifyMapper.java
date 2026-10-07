package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Notify;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotifyMapper extends BaseMapper<Notify> {

    /** 标记单条已读 */
    @Update("UPDATE notify SET is_read = 1, update_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /** 标记全部已读(调用方需同步 user.unread_notify_count 归零) */
    @Update("UPDATE notify SET is_read = 1, update_time = NOW() WHERE user_id = #{userId} AND is_read = 0")
    int markAllRead(@Param("userId") Long userId);

    /** 物理删除指向某帖子的通知(type=2被赞/3被评,target_id=postId 或 commentId) */
    @Delete("DELETE FROM notify WHERE (type IN (2,3) AND target_id = #{targetId})")
    int hardDeleteByTarget(@Param("targetId") long targetId);
}
