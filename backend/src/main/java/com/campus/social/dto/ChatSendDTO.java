package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息请求（F3 免好友搭话）。
 *
 * <p>{@code content} 上限 500：首条搭话前端限制 50 字便于快速表达，
 * 但服务端统一放宽到 500 —— 熟人之间的正常对话不该被搭话的约束连累，
 * 护栏管的是「能不能发」，不是「能发多长」。
 */
@Data
public class ChatSendDTO {

    @NotNull(message = "接收方不能为空")
    private Long toUserId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 500, message = "消息内容最多 500 字")
    private String content;

    /** 引用回复的消息 id，可空；非空时 service 会校验其与本次会话同属 */
    private Long replyToId;
}