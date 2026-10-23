package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 会话偏好设置（手机端右滑：免打扰 / 置顶 / 删除）。
 *
 * <p>三个开关全部可空，只改传了的那几个 —— 前端点「置顶」只发 pinned，
 * 不会把 muted 一起覆盖成 false。
 *
 * <p>删除不需要前端算 lastMsgId：服务端按 kind 取当时的最后一条消息 id
 * 写进 hidden_upto，客户端传的删不准也不会误伤新消息。
 */
@Data
public class ChatPrefDTO {

    /** dm = 私信（targetId 是对方 userId）；group = 群聊（targetId 是 groupId） */
    @NotNull(message = "会话类型不能为空")
    @Pattern(regexp = "dm|group", message = "会话类型只能是 dm 或 group")
    private String kind;

    @NotNull(message = "会话对象不能为空")
    private Long targetId;

    /** 置顶 */
    private Boolean pinned;

    /** 免打扰 */
    private Boolean muted;

    /** 从我的列表里删除（对方再发消息会自动回来） */
    private Boolean hidden;
}
