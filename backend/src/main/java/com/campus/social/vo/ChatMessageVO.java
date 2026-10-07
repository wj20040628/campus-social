package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessageVO {
    private Long id;
    private Long convId;
    private Long senderId;
    private Long receiverId;
    private String content;
    /** 0未读 1已读(receiver 侧) */
    private Integer status;
    /** 0正常 1已撤回 */
    private Boolean recalled;
    /** 引用回复:被引消息 id(可空) */
    private Long replyToId;
    /** 引用回复:被引消息片段(前 30 字,已撤回的被引消息显示"(已撤回)") */
    private String replyToContent;
    private LocalDateTime createTime;
    /** 是否我自己发的 */
    private boolean fromMe;
}
