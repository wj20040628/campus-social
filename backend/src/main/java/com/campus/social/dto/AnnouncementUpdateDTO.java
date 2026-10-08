package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编辑公告（F5 管理端，openapi {@code AnnouncementPatchRequest}）。
 *
 * <p>所有字段可选：只传要改的，未传的保持原值。
 * 这是 PATCH 语义——重发一整份公告会让管理员在改一个错别字时
 * 不得不重新填标题正文，且中途关掉页面就把草稿弄丢了。
 *
 * <p>{@code status} 只接受 1上架 / 2下架，<b>不允许传 0 草稿</b>：
 * 草稿只由创建接口产生。把已发布的公告打回草稿会让它从所有人的前台消失，
 * 却还留着全部已读回执，管理员自己都找不回来——这类操作应通过新建草稿实现。
 */
@Data
public class AnnouncementUpdateDTO {

    @Size(max = 60, message = "标题最多 60 字")
    private String title;

    @Size(max = 5000, message = "正文最多 5000 字")
    private String content;

    @Min(value = 1, message = "公告分类不合法")
    @Max(value = 6, message = "公告分类不合法")
    private Integer category;

    /** 置顶开关：0 取消、1 置顶。null 表示不改 */
    @Min(value = 0, message = "置顶标记只能是 0 或 1")
    @Max(value = 1, message = "置顶标记只能是 0 或 1")
    private Integer isTop;

    private LocalDateTime topExpireTime;

    /** 1上架 2下架；null 表示不改 */
    @Min(value = 1, message = "状态只能是 1 上架或 2 下架")
    @Max(value = 2, message = "状态只能是 1 上架或 2 下架")
    private Integer status;
}