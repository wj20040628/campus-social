package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ActivitySignup;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivitySignupMapper extends BaseMapper<ActivitySignup> {

    /**
     * 幂等键插入:重复报名无副作用(uk_as)
     * ON DUPLICATE KEY UPDATE id = id  → 等价"已存在则不报错"
     */
    @Insert("INSERT INTO activity_signup (activity_id, user_id) VALUES (#{activityId}, #{userId}) " +
            "ON DUPLICATE KEY UPDATE id = id")
    int insertIdempotent(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /**
     * 物理删除某人对某活动的报名(取消报名用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_as(activity_id,user_id) 继续锁住,
     * 再次报名 insertIdempotent 虽不报错,但计数/状态会错乱。取消报名应彻底删行。
     */
    @Delete("DELETE FROM activity_signup WHERE activity_id = #{activityId} AND user_id = #{userId}")
    int hardDelete(@Param("activityId") long activityId, @Param("userId") long userId);
}
