package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    /** 建群/拉人时原子累加 member_count(+delta) */
    @Update("UPDATE `group` SET member_count = member_count + #{delta}, last_msg_id = #{lastMsgId}, update_time = NOW() WHERE id = #{groupId}")
    int touchCountAndLastMsg(@Param("groupId") Long groupId, @Param("delta") int delta, @Param("lastMsgId") Long lastMsgId);

    /** 仅更新最近一条群消息 id(发消息时用) */
    @Update("UPDATE `group` SET last_msg_id = #{lastMsgId}, update_time = NOW() WHERE id = #{groupId}")
    int touchLastMsg(@Param("groupId") Long groupId, @Param("lastMsgId") Long lastMsgId);

    /** 减 1 成员数(踢人/退群用)。与 touchCountAndLastMsg 分开:它不碰 last_msg_id */
    @Update("UPDATE `group` SET member_count = member_count - 1, update_time = NOW() WHERE id = #{groupId} AND member_count > 0")
    int decMemberCount(@Param("groupId") Long groupId);

    /** 群主转让:把 owner_id 改指到新群主(群主本人不退出,降为普通成员) */
    @Update("UPDATE `group` SET owner_id = #{newOwnerId}, update_time = NOW() WHERE id = #{groupId}")
    int transferOwner(@Param("groupId") Long groupId, @Param("newOwnerId") Long newOwnerId);
}
