package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 设置我的标签后的响应（openapi.yaml UserTagsPutResponse 的 data）。
 */
@Data
@Builder
@AllArgsConstructor
public class UserTagsVO {

    private Long userId;

    /** 覆盖后的最终标签集合 */
    private List<TagItemVO> tags;

    /** 是否调用了清空(空数组语义),供前端区分「清空成功」与「设置成功」 */
    private Boolean cleared;

    public static UserTagsVO of(Long userId, List<TagItemVO> tags) {
        return UserTagsVO.builder()
                .userId(userId)
                .tags(tags)
                .cleared(tags != null && tags.isEmpty())
                .build();
    }
}
