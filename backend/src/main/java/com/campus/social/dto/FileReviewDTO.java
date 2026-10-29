package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员文件审核：action=approve / reject，驳回必须给理由。
 */
@Data
public class FileReviewDTO {

    @NotBlank(message = "审核动作不能为空")
    @Pattern(regexp = "approve|reject", message = "审核动作只能是 approve / reject")
    private String action;

    @Size(max = 200, message = "理由最多 200 字")
    private String reason;
}
