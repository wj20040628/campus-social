package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 已过审文件下架。理由必填：下架会通知上传者并影响信誉加分。
 */
@Data
public class FileTakedownDTO {

    @NotBlank(message = "下架理由不能为空")
    @Size(max = 200, message = "理由最多 200 字")
    private String reason;
}
