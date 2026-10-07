package com.campus.social.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewRejectDTO {
    @Size(max = 512, message = "理由不超过 512 字")
    private String reason;
}
