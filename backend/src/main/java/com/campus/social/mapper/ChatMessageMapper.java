package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 查某人发给指定会话的、对方尚未标记已读(status=0)的消息 id(消息级已读用) */
    @Select("SELECT id FROM chat_message WHERE conv_id = #{convId} AND sender_id = #{senderId} " +
            "AND status = 0 AND is_deleted = 0")
    List<Long> selectUnreadIdsBySender(@Param("convId") Long convId, @Param("senderId") Long senderId);

    /** 把发送方某批消息标为已读(receiver 已读回执) */
    @Update("<script>" +
            "UPDATE chat_message SET status = 1, update_time = NOW() WHERE id IN " +
            "<foreach collection='ids' item='i' open='(' close=')' separator=','>#{i}</foreach>" +
            "</script>")
    int markSenderMessagesRead(@Param("ids") List<Long> ids);

    /**
     * 对方是否在这个会话里回复过我（2026-10-28）。
     *
     * <p>回复是比关注更强的“愿意继续聊”信号：对方回过一条，就不该再受
     * 每日 1 条限制。撤回的消息不算——撤回意味着他不想留下这条回复。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM chat_message "
            + "WHERE sender_id = #{peer} AND receiver_id = #{me} "
            + "  AND is_recalled = 0 AND is_deleted = 0)")
    boolean existsReplyFromPeer(@Param("me") long me, @Param("peer") long peer);
}
