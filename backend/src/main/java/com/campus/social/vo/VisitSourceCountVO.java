package com.campus.social.vo;

import lombok.Data;

/** 今日游客会话的来源分组（最多 5 组，按会话数倒序）。 */
@Data
public class VisitSourceCountVO {

    private String source;

    private Long sessions;
}