package com.campus.social.dto;

import lombok.Data;

/** 单个媒体项 */
@Data
public class MediaItemDTO {
    /** "img" 或 "video" */
    private String type;
    private String url;
    /** 视频时长(秒) */
    private Integer dur;
}
