package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.GroupRead;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GroupReadMapper extends BaseMapper<GroupRead> {

    /** 我读到的最后一条群消息 id(水位;无记录返回 0) */
    @Select("SELECT COALESCE(MAX(last_read_msg_id), 0) FROM group_read " +
            "WHERE group_id = #{groupId} AND user_id = #{userId} AND is_deleted = 0")
    long lastRead(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /** 更新我的水位(幂等 upsert:ON DUPLICATE KEY UPDATE,uk_gr 保证一行) */
    @Insert("INSERT INTO group_read (group_id, user_id, last_read_msg_id) " +
            "VALUES (#{groupId}, #{userId}, #{lastReadMsgId}) " +
            "ON DUPLICATE KEY UPDATE last_read_msg_id = GREATEST(last_read_msg_id, #{lastReadMsgId}), update_time = NOW()")
    int upsertWater(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("lastReadMsgId") Long lastReadMsgId);

    /** 某群所有成员 id(推群已读回执给"我发给谁、谁读到了"用) */
    @Select("SELECT user_id FROM group_read WHERE group_id = #{groupId} AND is_deleted = 0")
    java.util.List<Long> readersOf(@Param("groupId") Long groupId);
}
