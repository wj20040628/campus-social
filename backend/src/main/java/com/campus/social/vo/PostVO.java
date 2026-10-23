package com.campus.social.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情/列表卡片
 * media 保留原始 JSON 字符串(由 Jackson 解析为 JsonNode 避免强类型)
 *
 * <p><b>为什么需要无参构造</b>：{@code @Builder} 只会生成一个全参构造，
 * Jackson 反序列化时需要「无参构造 + setter」这条路，否则会抛
 * {@code Cannot construct instance of PostVO (no Creators ...)}。
 * Feed 首屏缓存要把 PostVO 序列化进 Redis 再读回来，缺了它缓存命中就报错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    private Integer favoriteCount;
    private Integer viewCount;
    /** 话题名列表(快手式 #话题#) */
    private List<String> topics;
    private Double hotScore;
    private Long circleId;
    /**
     * 所属圈子名。{@code circleId} 非空但本字段为 null 时，表示该圈已解散/被封禁
     * （或查不到），前端此时<b>不渲染</b>圈子标签 —— 给一个点进去 404 的链接
     * 比不显示更糟。
     *
     * <p>为什么由服务端下发而不是前端拿 id 自己查：列表一页 20 条，前端逐条
     * 查圈名就是 20 次请求（N+1）。装配器已按页批量查回，一次查询覆盖整页。
     */
    private String circleName;
    private LocalDateTime createTime;
    /** 当前用户是否已赞 */
    private boolean liked;
    /** 当前用户是否已收藏 */
    private boolean favorited;
    /** 圈内置顶 */
    private boolean isTop;
}
