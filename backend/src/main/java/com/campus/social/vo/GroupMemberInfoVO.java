package com.campus.social.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 群信息头出参:群名/人数/群主/公告 + 成员名单。
 * 前端群顶栏据此渲染"群名 · N 人"、公告条、按角色显隐操作入口。
 */
@Data
@Builder
public class GroupMemberInfoVO {
    private Long groupId;
    private String name;
    private Long ownerId;
    /** 群公告(可空,空=无) */
    private String announcement;
    private int memberCount;
    /** 成员名单:群主排第一、调用者(我)标 isSelf */
    private List<MemberItem> members;

    /** 单个成员项:公开用户卡片 + 角色标 + 是否调用者本人 */
    @Data
    @Builder
    public static class MemberItem {
        /** 成员公开卡片(昵称/头像/主页入口等) */
        private UserVO user;
        /** 是否群主(1 群主 / 0 普通成员,对应 group_member.role)
         *  @JsonProperty 强制 JSON key = isOwner,与契约/前端对齐
         *  (否则 Lombok boolean 的 getter isOwner() 会被 Jackson 序列化成 owner) */
        @JsonProperty("isOwner")
        private boolean isOwner;
        /** 是否调用者本人(前端标"我"、隐藏对群主的操作) */
        @JsonProperty("isSelf")
        private boolean isSelf;
        /** 该成员是否被群主禁言(前端在名单里显"已禁言"标;群主恒 false) */
        @JsonProperty("muted")
        private boolean muted;
        /**
         * 入群日期(yyyy-MM-dd,来自 group_member.create_time)。
         * 用 String 而不是 LocalDateTime:前端只需要「2026-10-03 入群」这一种写法,
         * 传 datetime 会让每个调用方各自格式化一次,而时区/空格 T 的处理必然各写各的。
         */
        private String joinDate;
    }
}
