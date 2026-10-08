package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 标签分组（openapi.yaml TagGroup）：按 category 分组，组内按 sort 升序。
 */
@Data
@Builder
public class TagGroupVO {

    private Integer category;

    private String categoryName;

    private List<TagItemVO> tags;
}
