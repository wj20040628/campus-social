package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/** 集市活动「重新激活」入参。 */
@Data
public class MktReactivDTO {

    /** 新的投放截止时间，now < newExpire <= now + 12h（service 校验）。 */
    @NotNull(message = "截止时间不能为空")
    private LocalDateTime newExpire;
}
