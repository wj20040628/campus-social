package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 访问记录上报（2026-11-14）。
 *
 * <p>enter 与 ping 共用同一份结构：enter 只需要身份与入口，
 * ping 额外带 activeSeconds / ended。字段全部经过长度约束，
 * 防止公开接口被超长字符串写进数据库。
 */
@Data
public class VisitReportDTO {

    @NotBlank(message = "缺少访客标识")
    @Size(max = 36, message = "访客标识过长")
    private String visitorId;

    @NotBlank(message = "缺少会话标识")
    @Size(max = 36, message = "会话标识过长")
    private String sessionId;

    /** 入口路径，例如 /guest；ping 时可不传 */
    @Size(max = 64, message = "入口路径过长")
    private String entryPath;

    /** qr / guest_login / direct，或二维码链接的 from 参数 */
    @Size(max = 32, message = "来源标签过长")
    private String source;

    /** 是否游客会话 */
    private Boolean guest;

    /** 页面在前台的累计秒数 */
    @Min(value = 0, message = "停留时长不能为负")
    @Max(value = 86400, message = "停留时长超出范围")
    private Integer activeSeconds;

    /** 本次上报是否为会话结束 */
    private Boolean ended;
}