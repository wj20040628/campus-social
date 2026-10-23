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

    /**
     * 物理删除指向某帖子的全部通知(2026-10-23 修,审查 P0-9)。
     *
     * <p>旧写法 {@code WHERE type IN (2,3) AND target_id = #{targetId}} 有两重错误:
     * <b>误删</b>——评论/提及通知的 target_id 存的是 commentId,与 postId 两套独立自增
     * 必然重叠,删 post_id=57 会把 comment.id=57(别的帖子)上的通知一起删掉;
     * <b>漏删</b>——本帖所有评论通知与 type=9 提及、type=7/8 审核结果全不清,留死链。
     *
     * <p>notify.post_id 列本来就存在且点赞/评论/提及/审核通知都会写,按它删才是正确口径。
     */
    @Delete("DELETE FROM notify WHERE post_id = #{postId}")
    int hardDeleteByPostId(@Param("postId") long postId);
}
