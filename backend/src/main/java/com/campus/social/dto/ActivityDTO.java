package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityDTO {
    @NotBlank
    @Size(max = 128)
    private String title;

    private Long circleId;

    /** 名额上限,0 不限 */
    @NotNull
    private Integer capacity;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;
}
