package com.campus.social.vo;

import lombok.Data;

import java.util.List;

/**
 * 文件库分页（偏移分页，不是游标）。
 *
 * <p>文件列表要支持「最新 / 最多下载」两种排序，游标只对 id 单调的
 * 「最新」成立；为了让两种排序共用一套翻页契约，这里用 page/size。
 */
@Data
public class FilePageVO {

    private List<FileVO> list;

    private Long total;

    private Integer page;

    private Integer size;

    private Boolean hasMore;
}
