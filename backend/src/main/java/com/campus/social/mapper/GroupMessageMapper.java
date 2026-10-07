package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.GroupMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GroupMessageMapper extends BaseMapper<GroupMessage> {

    /** 我读到的水位之后(严格大于)、且非我发的群消息数 = 我侧群未读 */
    @Select("SELECT COUNT(*) FROM group_message m " +
            "WHERE m.group_id = #{groupId} AND m.is_deleted = 0 AND m.id > #{water} AND m.sender_id <> #{me}")
    int unreadCountAfter(@Param("groupId") Long groupId, @Param("me") Long me, @Param("water") Long water);

    /** 批量物理撤:把某条群消息置 is_recalled=1(幂等,重复调用无副作用) */
    @Update("UPDATE group_message SET is_recalled = 1, update_time = NOW() WHERE id = #{msgId} AND is_recalled = 0")
    int markRecalled(@Param("msgId") Long msgId);

    /** 取一批群消息里所有被引消息(批量回填引用片段,避免 N+1) */
    @Select("<script>" +
            "SELECT * FROM group_message WHERE is_deleted = 0 AND id IN " +
            "<foreach collection='ids' item='i' open='(' close=')' separator=','>#{i}</foreach>" +
            "</script>")
    List<GroupMessage> selectReplyTargets(@Param("ids") List<Long> ids);

    /** 某群最后一条群消息 id(水位取"我读到的最大 id",用于群已读) */
    @Select("SELECT MAX(id) FROM group_message WHERE group_id = #{groupId} AND is_deleted = 0")
    Long lastMsgId(@Param("groupId") Long groupId);
}
