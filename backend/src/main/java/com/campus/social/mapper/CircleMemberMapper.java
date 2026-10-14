package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.CircleMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {

    /**
     * 物理删除某人对某圈子的成员行(退圈用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_cm(circle_id,user_id) 继续锁住,
     * 再次入会撞 Duplicate entry。退圈应彻底删行。
     */
    @Delete("DELETE FROM circle_member WHERE circle_id = #{circleId} AND user_id = #{userId} AND role = #{role}")
    int hardDelete(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);

    /** 圈内封禁:置 ban_status / ban_until(东八区,NULL=长期);解除时 until 传 null */
    @Update("UPDATE circle_member SET ban_status = #{ban}, ban_until = #{until}, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND user_id = #{userId} AND is_deleted = 0")
    int setBan(@Param("circleId") long circleId, @Param("userId") long userId,
               @Param("ban") int ban, @Param("until") LocalDateTime until);

    /** 成员角色调整(任命/取消圈管理、移交圈主时翻转 role) */
    @Update("UPDATE circle_member SET role = #{role}, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND user_id = #{userId} AND is_deleted = 0")
    int setRole(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);
}
