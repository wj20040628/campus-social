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
     * 报名幂等键插入。并发重复报名由 uk_as(activity_id,user_id) 抛
     * DuplicateKeyException 兜底，由 service 捕获后按幂等返回。
     *
     * <p>不能用 {@code ON DUPLICATE KEY UPDATE id = id} + 看返回值判断：
     * mysql-connector-j 默认携带 CLIENT_FOUND_ROWS，冲突赋回原值时返回 1 而非 0，
     * 同一用户并发报名会把 activity.current_count 加多次。
     */
    @Insert("INSERT INTO activity_signup (activity_id, user_id) VALUES (#{activityId}, #{userId})")
    int insertSignup(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /**
     * 物理删除某人对某活动的报名(取消报名用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_as(activity_id,user_id) 继续锁住,
     * 再次报名会撞 uk_as 被当作重复，计数/状态会错乱。取消报名应彻底删行。
     */
    @Delete("DELETE FROM activity_signup WHERE activity_id = #{activityId} AND user_id = #{userId}")
    int hardDelete(@Param("activityId") long activityId, @Param("userId") long userId);
}
