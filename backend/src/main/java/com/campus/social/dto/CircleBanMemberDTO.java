package com.campus.social.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 圈内封禁请求:until 为 null = 长期封禁;传东八区 LocalDateTime 表示到期自动解除。
 */
@Data
public class CircleBanMemberDTO {
    /** 封禁到期时间(东八区);null=长期 */
    private LocalDateTime until;
}
