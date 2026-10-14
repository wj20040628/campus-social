package com.campus.social.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情/列表卡片
 * media 保留原始 JSON 字符串(由 Jackson 解析为 JsonNode 避免强类型)
 */
@Data
@Builder
public class PostVO {
    private Long id;
    private Long authorId;
    /** 作者卡片(脱敏) */
    private UserVO author;
    private String content;
    /** 解析后的 JSON 节点 */
    private JsonNode media;
    private Integer status;          // 0审核中 1通过 2下架 3删除
    private Integer likeCount;
    private Integer commentCount;
    private Integer viewCount;
    /** 话题名列表(快手式 #话题#) */
    private List<String> topics;
    private Double hotScore;
    private Long circleId;
    private LocalDateTime createTime;
    /** 当前用户是否已赞 */
    private boolean liked;
    /** 圈内置顶 */
    private boolean isTop;
}
