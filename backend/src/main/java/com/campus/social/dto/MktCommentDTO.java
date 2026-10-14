package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 集市活动留言入参。敏感词 level=2 命中拒收（service 校验）。 */
@Data
public class MktCommentDTO {

    @NotBlank(message = "留言内容不能为空")
    @Size(max = 500, message = "留言最多 500 字")
    private String content;
}
