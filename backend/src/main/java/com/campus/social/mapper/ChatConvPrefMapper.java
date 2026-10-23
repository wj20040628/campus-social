package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ChatConvPref;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatConvPrefMapper extends BaseMapper<ChatConvPref> {

    /** 一次取某用户某类型的全部偏好（列表用；会话数在校园场景是几十条，全量取无压力） */
    @Select("SELECT * FROM chat_conv_pref WHERE user_id = #{userId} AND kind = #{kind}")
    List<ChatConvPref> selectByUserAndKind(@Param("userId") Long userId, @Param("kind") String kind);

    /** 单条偏好（写操作的读-改-写起点） */
    @Select("SELECT * FROM chat_conv_pref WHERE user_id = #{userId} AND kind = #{kind} AND target_id = #{targetId}")
    ChatConvPref selectOne(@Param("userId") Long userId,
                           @Param("kind") String kind,
                           @Param("targetId") Long targetId);
}
