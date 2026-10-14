package com.campus.social.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 集市活动展示 VO（广场 / 我的发布 / 详情共用）。
 * 广场行不含 status；「我的发布」行带 status（服务端推导的 4 态）。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MktActivityVO {
    private Long id;
    private Long userId;
    /** 发布者昵称（脱敏由 UserVoAssembler 统一处理） */
    private String nickname;
    private String avatarUrl;
    private String category;
    private String subTag;
    private String title;
    private String content;
    private Integer targetNum;
    private BigDecimal price;
    private String location;
    private Integer isEnable;
    private LocalDateTime expireTime;
    private Integer isFull;
    private LocalDateTime createTime;
    private LocalDateTime lastLaunchTime;
    /** 仅「我的发布」填充：LAUNCHING/FULL/PAUSED/EXPIRED */
    private MktStatus status;
}
