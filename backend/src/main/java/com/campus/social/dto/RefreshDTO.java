package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshDTO {
    @NotBlank
    private String refreshToken;
}
