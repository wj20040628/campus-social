package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReservedNameVO {
    private Long id;
    private String name;
    private String note;
    private LocalDateTime createTime;
}
