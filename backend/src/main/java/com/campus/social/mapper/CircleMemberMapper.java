package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.CircleMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {

    /**
     * 物理删除某人对某圈子的成员行(退圈用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_cm(circle_id,user_id) 继续锁住,
     * 再次入会撞 Duplicate entry。退圈应彻底删行。
     */
    @Delete("DELETE FROM circle_member WHERE circle_id = #{circleId} AND user_id = #{userId} AND role = #{role}")
    int hardDelete(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);
}
