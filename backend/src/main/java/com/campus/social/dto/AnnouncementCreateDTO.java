package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布公告（F5 管理端，openapi {@code AnnouncementCreateRequest}）。
 *
 * <p>{@code isTop} 用 Integer 0/1 而非 Boolean：契约如此，且它直接映射
 * {@code announcement.is_top} 列。改成 boolean 会让 DTO 与库表之间多一层转换，
 * 而收益仅是少写一个 {@code == 1}。
 *
 * <p>{@code publishNow} 决定是存草稿还是直接发布。用显式的布尔字段
 * 而不是让前端传 {@code status}：status 有三个取值（0草稿/1已发布/2已下架），
 * 「已下架」不该由创建接口产生，传错了会造出一条永远发不出去的公告。
 */
@Data
public class AnnouncementCreateDTO {

    @NotBlank(message = "公告标题不能为空")
    @Size(min = 2, max = 60, message = "标题需2 到 60 字")
    private String title;

    @NotBlank(message = "公告正文不能为空")
    @Size(max = 5000, message = "正文最多 5000 字")
    private String content;

    /** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
    @NotNull(message = "公告分类不能为空")
    @Min(value = 1, message = "公告分类不合法")
    @Max(value = 6, message = "公告分类不合法")
    private Integer category;

    /** 0否 1是。传 1 时 topExpireTime 必填——永不过期的置顶等于永久置顶 */
    @Min(value = 0, message = "置顶标记只能是 0 或 1")
    @Max(value = 1, message = "置顶标记只能是 0 或 1")
    private Integer isTop = 0;

    private LocalDateTime topExpireTime;

    /** false 表示存为草稿 */
    private Boolean publishNow = Boolean.TRUE;
}