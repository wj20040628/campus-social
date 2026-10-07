package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Relation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RelationMapper extends BaseMapper<Relation> {

    /**
     * 物理删除某条关系(取消关注/好友/拉黑用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_rel(from_user_id,to_user_id,type) 继续锁住,
     * 再次 follow/加友/拉黑 时 insert 撞 Duplicate entry。取消关系应彻底删行。
     */
    @Delete("DELETE FROM relation WHERE from_user_id = #{fromUserId} AND to_user_id = #{toUserId} AND type = #{type}")
    int hardDelete(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId, @Param("type") int type);
}
