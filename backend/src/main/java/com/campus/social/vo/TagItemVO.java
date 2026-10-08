package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 兴趣标签项（openapi.yaml TagItem）。
 * 与 AnnouncementPublisher 一样是嵌套对象，故独立成文件而非塞进大 VO。
 */
@Data
@Builder
public class TagItemVO {

    private Long id;

    private String name;

    /** 1兴趣 2游戏 3运动 4学习 5生活 */
    private Integer category;

    /** 被多少用户选中 */
    private Integer useCount;
}
