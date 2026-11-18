package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 批量在线查询结果：仅返回在线且未隐身的 userId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresenceStatusVO {
    private List<Long> online;
}
