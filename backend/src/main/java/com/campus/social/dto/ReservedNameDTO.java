package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预留官方圈名:运营后台增删。name 撞既有/既有已删的圈子名时由 service 拦。
 */
@Data
public class ReservedNameDTO {
    @NotNull(message = "圈子名不能为空")
    private String name;
    private String note;
}
