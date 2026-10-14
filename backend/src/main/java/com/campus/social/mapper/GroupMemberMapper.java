package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.GroupMember;
import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    /** 某群所有成员 user_id(发消息时多播用,调用方自行剔除发送者) */
    @Select("SELECT user_id FROM group_member WHERE group_id = #{groupId} AND is_deleted = 0")
    List<Long> memberIds(@Param("groupId") Long groupId);

    /** 某群成员数(软删口径) */
    @Select("SELECT COUNT(*) FROM group_member WHERE group_id = #{groupId} AND is_deleted = 0")
    int countByGroup(@Param("groupId") Long groupId);

    /** 我加入的群 id 列表 */
    @Select("SELECT group_id FROM group_member WHERE user_id = #{me} AND is_deleted = 0")
    List<Long> groupIdsByUser(@Param("me") Long me);

    /** 判断某用户是否是某群成员(1/0) */
    @Select("SELECT COUNT(*) FROM group_member WHERE group_id = #{groupId} AND user_id = #{userId} AND is_deleted = 0")
    long isMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 批量物理删群成员(退群/解散)。关系表用物理删,软删留行会锁 uk_gm,再入群撞 Duplicate entry。
     */
    @Delete("<script>" +
            "DELETE FROM group_member WHERE group_id = #{groupId} AND user_id IN " +
            "<foreach collection='userIds' item='i' open='(' close=')' separator=','>#{i}</foreach>" +
            "</script>")
    int hardDeleteByGroupAndUsers(@Param("groupId") Long groupId, @Param("userIds") Collection<Long> userIds);

    /** 改某成员在群里的角色(1群主 2成员)。群主转让/降权时用,不动关系行本身 */
    @Update("UPDATE group_member SET role = #{role}, update_time = NOW() " +
            "WHERE group_id = #{groupId} AND user_id = #{userId} AND is_deleted = 0")
    int updateRole(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("role") int role);

    /** 设/清某成员被禁言(群主用):1=禁言 0=解除。群主自己不应被调 */
    @Update("UPDATE group_member SET muted = #{muted}, update_time = NOW() " +
            "WHERE group_id = #{groupId} AND user_id = #{userId} AND is_deleted = 0")
    int setMuted(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("muted") int muted);

    /** 全员禁言/解除(群主用):除群主外全部成员 muted 置值,幂等可重放 */
    @Update("UPDATE group_member SET muted = #{muted}, update_time = NOW() " +
            "WHERE group_id = #{groupId} AND user_id <> #{ownerId} AND is_deleted = 0")
    int setAllMuted(@Param("groupId") Long groupId, @Param("ownerId") Long ownerId, @Param("muted") int muted);

    /** 某成员在该群的 muted(0/1);行不存在(非成员)返回 null */
    @Select("SELECT muted FROM group_member WHERE group_id = #{groupId} AND user_id = #{userId} AND is_deleted = 0")
    Integer mutedOf(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /** 某群每个成员的 muted 映射(群信息头名单回填用,避免 N+1)。返回行含 user_id + muted */
    @Select("SELECT user_id, muted FROM group_member WHERE group_id = #{groupId} AND is_deleted = 0")
    List<GroupMember> memberMutedRows(@Param("groupId") Long groupId);
}
