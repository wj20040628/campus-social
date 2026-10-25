package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 直接封禁用户（openapi UserBanRequest，AdminUserController）。
 *
 * <p>与举报处置 ReportHandleDTO 的区别：那是「基于一条举报」的处置，
 * 本 DTO 是管理员在用户处置页**直接封人**——不要求先有人举报。
 * 没有这个入口时，管理员看到刷屏 spam 得先自己以用户身份举报、
 * 再去举报队列处置，绕一整圈。
 */
@Data
public class UserBanDTO {

    /** 0=永久；1/7/30 为常用档（与举报处置的 BAN_DAY_OPTIONS 同一套语义） */
    @NotNull(message = "封禁天数不能为空")
    @Min(value = 0, message = "封禁天数不能为负")
    @Max(value = 3650, message = "封禁天数最多 10 年")
    private Integer banDays;

    /**
     * 必填——与举报处置不同，这里没有举报内容做上下文，
     * 半年后回看 audit_log 时「为什么封」只有这一句话可依赖。
     */
    @NotBlank(message = "封禁理由不能为空")
    @Size(max = 200, message = "封禁理由最多 200 字")
    private String reason;
}
