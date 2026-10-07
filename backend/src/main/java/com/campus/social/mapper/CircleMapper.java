package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Circle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CircleMapper extends BaseMapper<Circle> {

    @Update("UPDATE circle SET member_count = member_count + #{delta}, update_time = NOW() WHERE id = #{circleId}")
    int adjustMemberCount(@Param("circleId") Long circleId, @Param("delta") int delta);

    /** 对账:重算 member_count */
    @Update("UPDATE circle c SET " +
            "  member_count = (SELECT COUNT(*) FROM circle_member cm WHERE cm.circle_id = c.id AND cm.is_deleted = 0), " +
            "  update_time = NOW()")
    int reconcileMemberCount();
}
