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

    /*
     * 年级与学院（2026-10-21 加）。
     *
     * 为什么加：成员列表从「只有圈主能看的管理面板」扩展到「所有人可见的成员页」后，
     * 光有昵称不足以让人判断「这个圈子的人跟我是不是一届/一个院」——
     * 而「圈子里有哪些人」正是决定要不要加入的主要依据。
     *
     * 只给名称不给 id：这两个字段在前端只用于展示，回传字典 id 会让 VO 多两个
     * 前端根本用不到的字段。也不给学号 —— SPEC AC-12 要求对外不显示学号。
     */
    private String grade;
    private String dept;
}
