package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 标签树响应（openapi.yaml TagTreeResponse 的 data）。
 */
@Data
@AllArgsConstructor
public class TagTreeVO {

    private List<TagGroupVO> groups;

    /** 标签总数,前端用来显示「共 N 个可选」 */
    private Integer total;
}
