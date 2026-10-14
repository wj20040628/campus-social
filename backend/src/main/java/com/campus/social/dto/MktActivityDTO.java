package com.campus.social.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 集市活动发布 / 编辑共用入参。
 * 字段长度/取值范围在 service 层二次校验（标题 15–25 字符、描述 1–500、
 * 投放时长 ≤12h、分类 9 选 1），防前端绕过；此处只拦明显的超长输入。
 */
@Data
public class MktActivityDTO {

    /** 一级分类（必填，9 选 1） */
    @Size(max = 32, message = "一级分类过长")
    private String category;

    /** 子关键词（可选） */
    @Size(max = 64, message = "子关键词过长")
    private String subTag;

    /** 活动标题（15–25 字符，service 校验） */
    @Size(max = 64, message = "标题过长")
    private String title;

    /** 详细描述（1–500 字，service 校验） */
    @Size(max = 500, message = "详情过长")
    private String content;

    /** 期望参与人数（可选，纯展示） */
    private Integer targetNum;

    /** 费用价格（可选，0=免费） */
    private BigDecimal price;

    /** 活动地点（可选） */
    @Size(max = 128, message = "地点过长")
    private String location;

    /**
     * 投放截止时间（ISO LocalDateTime）。发布必填；编辑不带此字段（不改 expire_time）。
     * service 校验：now < expire_time <= now + 12h。
     */
    private java.time.LocalDateTime expireTime;
}
