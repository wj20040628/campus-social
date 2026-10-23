package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的群会话列表项(群卡片 + 我侧未读 + 群主)
 */
@Data
@Builder
public class GroupVO {
    private Long id;
    private String name;
    private String avatarUrl;
    private Long ownerId;
    /** 群主卡片 */
    private UserVO owner;
    private int memberCount;
    /** 群公告(可空,空=无)。列表卡片不展示,留作群信息头来源;与 GroupMemberInfoVO 同口径 */
    private String announcement;
    /** 我加入的群:当前用户侧未读(水位后非我发的条数) */
    private int myUnread;
    private Long lastMsgId;
    private String lastPreview;
    private LocalDateTime lastMsgTime;

    /** 我把它置顶了 */
    private boolean pinned;

    /** 我把它设成免打扰了 */
    private boolean muted;
}
