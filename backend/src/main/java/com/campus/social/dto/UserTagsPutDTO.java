package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设置我的兴趣标签（PUT /user/tags）。
 *
 * <p>语义是<b>全量覆盖</b>而非增量追加：传入的 tagIds 即最终集合。
 * 允许传空数组表示清空——这是有意的，「清空我的标签」是一个真实需求
 * （用户换了口味想重新选），不是参数错误。
 *
 * <p>上限 8 个在 service 层校验并回 422，因为 openapi 明确要求该场景返回
 * {@code UNPROCESSABLE} 而非 {@code BAD_REQUEST}；这里只做「数组本身」的形状校验。
 */
public class UserTagsPutDTO {

    @NotNull(message = "标签列表不能为 null")
    @Size(max = 50, message = "标签数量超出可接受范围")
    private List<Long> tagIds;

    public List<Long> getTagIds() {
        return tagIds;
    }

    public void setTagIds(List<Long> tagIds) {
        this.tagIds = tagIds;
    }
}
