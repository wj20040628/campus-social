package com.campus.social.dto;

import lombok.Data;

/**
 * 移帖请求:toCircleId 为 null 表示移回广场(脱离任何圈子)。
 */
@Data
public class CircleMovePostDTO {
    private Long toCircleId;
}
