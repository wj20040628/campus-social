package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 意见反馈管理端列表行（2026-10-18）。
 *
 * <p>字段与 {@code feedback} 表一一对应，另加联表带出的 {@code userNickname}。
 * 手机端要在 375px 视口里完成判断，所以提交人昵称必须直接带上 ——
 * 让前端再发一轮请求去查昵称，管理员每条反馈就多一次等待。
 *
 * <p>{@code mediaJson} 原样透传字符串，由前端解析成缩略图：
 * 后端不拆成 {@code List<MediaVO>} 是因为这层只有一个消费方（管理端页面），
 * 而拆开就得多一个 VO + 一层转换，而 JSON 本身已经是数组结构。
 */
@Data
public class FeedbackAdminItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 提交人昵称，联表带出；用户被注销时为 null，前端降级为「已注销用户」 */
    private String userNickname;

    /** 1功能建议 2使用问题 3内容问题 4界面体验 5其他 */
    private Integer category;

    private String content;

    /** {@code [{"type":"img","url":"..."}]}，空表示没传媒体 */
    private String mediaJson;

    /** 0待处理 1已采纳 2已忽略 */
    private Integer status;

    private Long handlerId;

    private LocalDateTime handleTime;

    private String handleNote;

    private LocalDateTime createTime;
}
