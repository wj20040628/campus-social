package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    /**
     * 建群/拉人时原子累加 member_count(+delta)。
     * lastMsgId 为空时必须保留原水位：拉人不是发消息，不能把群列表预览清空。
     */
    @Update("UPDATE `group` SET member_count = member_count + #{delta}, "
            + "last_msg_id = COALESCE(#{lastMsgId}, last_msg_id), update_time = NOW() WHERE id = #{groupId}")
    int touchCountAndLastMsg(@Param("groupId") Long groupId, @Param("delta") int delta, @Param("lastMsgId") Long lastMsgId);

    /** 锁群行：邀请配额检查与插入必须串行，否则并发会突破群成员上限 */
    @Select("SELECT * FROM `group` WHERE id = #{groupId} AND is_deleted = 0 FOR UPDATE")
    Group lockById(@Param("groupId") Long groupId);

    /** 仅更新最近一条群消息 id(发消息时用) */
    @Update("UPDATE `group` SET last_msg_id = #{lastMsgId}, update_time = NOW() WHERE id = #{groupId}")
    int touchLastMsg(@Param("groupId") Long groupId, @Param("lastMsgId") Long lastMsgId);

    /** 减 1 成员数(踢人/退群用)。与 touchCountAndLastMsg 分开:它不碰 last_msg_id */
    @Update("UPDATE `group` SET member_count = member_count - 1, update_time = NOW() WHERE id = #{groupId} AND member_count > 0")
    int decMemberCount(@Param("groupId") Long groupId);

    /** 群主转让:把 owner_id 改指到新群主(群主本人不退出,降为普通成员) */
    @Update("UPDATE `group` SET owner_id = #{newOwnerId}, update_time = NOW() WHERE id = #{groupId}")
    int transferOwner(@Param("groupId") Long groupId, @Param("newOwnerId") Long newOwnerId);

    /** 以 group_member 实际行数重算 member_count(注销级联/数据修复用) */
    @Update("UPDATE `group` SET member_count = (SELECT COUNT(*) FROM group_member "
            + "WHERE group_id = #{groupId} AND is_deleted = 0), update_time = NOW() "
            + "WHERE id = #{groupId}")
    int recountMemberCount(@Param("groupId") Long groupId);

    /** 每日对账:按 group_member 实际行数重算全部未删除群 */
    @Update("UPDATE `group` g SET member_count = (SELECT COUNT(*) FROM group_member gm "
            + "WHERE gm.group_id = g.id AND gm.is_deleted = 0), update_time = NOW() "
            + "WHERE g.is_deleted = 0")
    int reconcileMemberCount();

    /** 对账前统计漂移群数,供日志/指标告警 */
    @Select("SELECT COUNT(*) FROM `group` g WHERE g.is_deleted = 0 "
            + "AND g.member_count <> (SELECT COUNT(*) FROM group_member gm "
            + "WHERE gm.group_id = g.id AND gm.is_deleted = 0)")
    int countMemberCountDrift();
}
