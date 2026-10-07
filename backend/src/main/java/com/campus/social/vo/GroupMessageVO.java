package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群消息出参(镜像 ChatMessageVO 形态,把 convId/单 receiver 换成 groupId)。
 * 群消息无"单一对方",fromMe 判"是否我发的",status 是发送方视角汇总(群内至少一人读到=1)。
 */
@Data
@Builder
public class GroupMessageVO {
    private Long id;
    private Long groupId;
    private Long senderId;
    private String content;
    /** 0未读 1已读(发送方视角汇总) */
    private Integer status;
    /** 0正常 1已撤回 */
    private Boolean recalled;
    /** 引用回复:被引群消息 id(可空) */
    private Long replyToId;
    /** 引用回复:被引群消息片段(前 30 字,已撤回的被引消息显示"(已撤回)") */
    private String replyToContent;
    /** 被引消息发送者昵称(群内要带名字) */
    private String replyToNickname;
    private LocalDateTime createTime;
    /** 是否我自己发的 */
    private boolean fromMe;
}
