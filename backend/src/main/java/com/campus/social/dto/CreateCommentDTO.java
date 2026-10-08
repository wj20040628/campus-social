package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 评论入参
 * parentId: 一级评论为 null;回复某条一级评论时填该评论 id
 * level: 1一级 2二级 3三级,≤3
 * replyToUserId: 被回复的人 id(仅三级评论有意义)。服务端会校验此人是否参与过本帖讨论,
 *   不满足则降级为通知帖子作者而非报错——「回复了人却没人收到」表现得像系统坏了,
 *   降级至少让通知有可解释的落点。详见 CommentServiceImpl#resolveLevel3Target
 * mentionUserIds: 被 @ 的用户 id 列表(昵称不唯一,前端选中候选时绑定唯一 id,后端按 id 精准发通知)
 */
@Data
public class CreateCommentDTO {

    @NotBlank
    @Size(max = 2000)
    private String content;

    private Long parentId;

    private Integer level;     // 默认 1

    /**
     * 被回复的人 id，仅三级评论有效。
     *
     * <p>服务端会校验此人是否参与过本帖评论；未参与则通知降级给帖子作者，<b>不报错</b>。
     * 注释写在这里而非只在实现类，是为了让调用方在读 DTO 时就能看到降级行为，
     * 不必去翻 service 才能知道「传错不会 400，但通知会落到作者头上」。
     */
    private Long replyToUserId;

    /** 被 @ 的用户 id(可空,多条 @ 传多个;空则不 @) */
    private List<Long> mentionUserIds;
}
