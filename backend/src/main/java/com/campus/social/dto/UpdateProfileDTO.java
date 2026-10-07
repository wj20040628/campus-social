package com.campus.social.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileDTO {
    @Size(max = 64)
    private String nickname;

    @Size(max = 16)
    private String grade;

    @Size(max = 64)
    private String dept;

    @Size(max = 512)
    private String avatarUrl;

    @Size(max = 32)
    private String studentNo;
}
