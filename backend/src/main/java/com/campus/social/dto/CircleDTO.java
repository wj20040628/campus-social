package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CircleDTO {
    @NotBlank
    @Size(max = 64)
    private String name;
}
