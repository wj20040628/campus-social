package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 评论入参
 * parentId: 一级评论为 null;回复某条一级评论时填该评论 id
 * level: 1一级 2二级 3三级,≤3
 * replyToUserId: 被回复的人 id(三级评论才有意义)
 * mentionUserIds: 被 @ 的用户 id 列表(昵称不唯一,前端选中候选时绑定唯一 id,后端按 id 精准发通知)
 */
@Data
public class CreateCommentDTO {

    @NotBlank
    @Size(max = 2000)
    private String content;

    private Long parentId;

    private Integer level;     // 默认 1

    private Long replyToUserId;

    /** 被 @ 的用户 id(可空,多条 @ 传多个;空则不 @) */
    private List<Long> mentionUserIds;
}
