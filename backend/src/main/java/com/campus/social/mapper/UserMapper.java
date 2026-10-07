package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 原子自增/自减计数,防并发丢失 */
    @Update("UPDATE user SET follower_count = follower_count + #{delta}, update_time = NOW() WHERE id = #{userId}")
    int adjustFollowerCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET following_count = following_count + #{delta}, update_time = NOW() WHERE id = #{userId}")
    int adjustFollowingCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET unread_notify_count = GREATEST(0, unread_notify_count + #{delta}), update_time = NOW() WHERE id = #{userId}")
    int adjustUnreadNotifyCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET unread_notify_count = 0, update_time = NOW() WHERE id = #{userId}")
    int clearUnreadNotifyCount(@Param("userId") Long userId);

    /** 对账:从 relation 表重算某用户的 follower_count / following_count */
    @Update("UPDATE user u SET " +
            "  follower_count = (SELECT COUNT(*) FROM relation r WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  following_count = (SELECT COUNT(*) FROM relation r WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  update_time = NOW() " +
            "WHERE u.id = #{userId}")
    int reconcileCounts(@Param("userId") Long userId);

    /** 全量对账:一次把所有用户的 follower/following 重算(凌晨跑,简单可靠) */
    @Update("UPDATE user u SET " +
            "  follower_count = (SELECT COUNT(*) FROM relation r WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  following_count = (SELECT COUNT(*) FROM relation r WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  update_time = NOW()")
    int reconcileAll();
}
