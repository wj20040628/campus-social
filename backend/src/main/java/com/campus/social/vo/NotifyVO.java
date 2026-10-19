package com.campus.social.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotifyVO {
    private Long id;
    /** 1被关注 2被赞 3被评 4私信 5活动 */
    private Integer type;
    private Long targetId;
    /** 被指向的帖子 id(点赞/评论跳详情用);旧数据/其他类型可能为 null */
    @JsonProperty("postId")
    private Long postId;
    private Long actorId;
    private UserVO actor;
    /** 关联对象摘要(帖子标题/评论摘要等) */
    private String summary;
    /** 自由文本通知内容(如反馈回复原文);非文本类通知为 null */
    private String content;
    /**
     * boolean + Lombok @Data 的 getter 是 isRead(),Jackson 默认序列化成 "read"(去 is 前缀),
     * 前端读的是 isRead → 恒 undefined,导致「新」标记永远显示。
     * 显式指定 JSON 字段名,前后端对齐。
     */
    @JsonProperty("isRead")
    private boolean isRead;
    private LocalDateTime createTime;
}
