package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 推荐圈子 —— mapper 层行投影。
 *
 * <p>与 {@link RecommendCircleVO} 分开的原因同 {@link RecommendUserRow}：
 * {@code createTime} 只用于服务层判断是否需要回传，不属于 HTTP 契约字段。
 * 另建一个类而不是用 {@code Map<String,Object>}：
 * Map 改列名编译无感知，只会在运行期静默变 null。
 */
@Data
public class RecommendCircleRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Integer memberCount;

    private LocalDateTime createTime;
}
