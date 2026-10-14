package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 圈子成员管理项(圈主/圈管理面板用)。
 * 不复用 UserVO:这是「某人在本圈里的身份」,role/banStatus 是本圈作用域的,
 * 混进 UserVO 会让通用用户卡片多出一堆 null。
 */
@Data
@Builder
public class CircleMemberVO {
    private Long userId;
    private String nickname;
    private String avatarUrl;
    /** 1圈主 2圈管理 3成员 */
    private int role;
    /** 圈内封禁 0正常 1封禁 */
    private int banStatus;
    /** 封禁到期(NULL=长期),未封禁为 null */
    private LocalDateTime banUntil;
    private LocalDateTime joinTime;
}
