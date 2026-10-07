package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ChatConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    /** 查我参与的所有会话,按最近一条消息时间倒序(联表 chat_message 取 max) */
    @Select("<script>" +
            "SELECT c.* FROM chat_conversation c " +
            "LEFT JOIN chat_message m ON m.conv_id = c.id AND m.is_deleted = 0 " +
            "WHERE (c.member_a = #{me} OR c.member_b = #{me}) AND c.is_deleted = 0 " +
            "GROUP BY c.id " +
            "ORDER BY COALESCE(MAX(m.create_time), c.create_time) DESC, c.id DESC" +
            "</script>")
    List<ChatConversation> selectByUser(@Param("me") Long me);

    @Update("UPDATE chat_conversation SET unread_b = 0, update_time = NOW() WHERE id = #{convId}")
    int clearUnreadB(@Param("convId") Long convId);

    @Update("UPDATE chat_conversation SET unread_a = 0, update_time = NOW() WHERE id = #{convId}")
    int clearUnreadA(@Param("convId") Long convId);

    @Update("UPDATE chat_conversation SET unread_a = unread_a + #{delta}, last_msg_id = #{lastMsgId}, update_time = NOW() WHERE id = #{convId}")
    int touchUnreadA(@Param("convId") Long convId, @Param("delta") int delta, @Param("lastMsgId") Long lastMsgId);

    @Update("UPDATE chat_conversation SET unread_b = unread_b + #{delta}, last_msg_id = #{lastMsgId}, update_time = NOW() WHERE id = #{convId}")
    int touchUnreadB(@Param("convId") Long convId, @Param("delta") int delta, @Param("lastMsgId") Long lastMsgId);

    /**
     * 幂等建会话:同一对用户归一化(member_a < member_b)后撞 uk_conv。
     * 用 LAST_INSERT_ID(id) 让两种路径都能取回会话 id:
     *  - 新建成功:LAST_INSERT_ID 被设为自增 id → 返回新 id
     *  - 已存在(ON DUPLICATE 命中):UPDATE 分支把 last_msg_id 更新为 LAST_INSERT_ID(原 id) → 也取回原 id
     * 调用方执行后可直接 SELECT LAST_INSERT_ID() 拿 convId,免并发首发时查不到→双双 insert 撞键。
     */
    @Insert("INSERT INTO chat_conversation (member_a, member_b, unread_a, unread_b, last_msg_id) " +
            "VALUES (#{memberA}, #{memberB}, 0, 0, #{lastMsgId}) " +
            "ON DUPLICATE KEY UPDATE last_msg_id = LAST_INSERT_ID(id), id = LAST_INSERT_ID(id)")
    int upsertConversation(@Param("memberA") Long memberA, @Param("memberB") Long memberB, @Param("lastMsgId") Long lastMsgId);

    /** 取上一次 upsertConversation 的 LAST_INSERT_ID() 作为 convId */
    @Select("SELECT LAST_INSERT_ID()")
    Long lastConvId();
}
