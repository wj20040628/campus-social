package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话偏好：置顶 / 免打扰 / 从列表删除。
 *
 * <p>按<b>人</b>存，不按会话行存：同一对用户共享一条 chat_conversation，
 * 但「我把它钉上去」不该影响对方。（kind, targetId）唯一，
 * 私信 targetId = 对方 userId，群聊 targetId = groupId。
 *
 * <p>没有 @TableLogic：这张表是纯偏好，删了就是没设置过，
 * 不存在「软删还要留着审计」的需求；而且过滤条件都显式写 hidden。
 */
@Data
@TableName("chat_conv_pref")
public class ChatConvPref implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String KIND_DM = "dm";
    public static final String KIND_GROUP = "group";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 偏好归属人 */
    private Long userId;

    /** dm | group */
    private String kind;

    /** dm = 对方 userId；group = group.id */
    private Long targetId;

    private Integer pinned;
    private Integer muted;
    private Integer hidden;

    /**
     * 删除当时的最后一条消息 id。
     * 列表过滤条件是 hidden=1 且 lastMsgId &lt;= hiddenUpto：
     * 来了新消息 lastMsgId 变大，会话自动回到列表，发消息链路不用回写。
     */
    private Long hiddenUpto;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
