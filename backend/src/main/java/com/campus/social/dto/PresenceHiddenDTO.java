package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 隐身开关请求（2026-11-13）。 */
@Data
public class PresenceHiddenDTO {
    @NotNull(message = "hidden 不能为空")
    private Boolean hidden;
}
