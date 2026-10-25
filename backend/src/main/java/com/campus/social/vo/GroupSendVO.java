package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 发群消息响应（2026-10-24 从裸 msgId 升级）。
 *
 * <p>带上打码后的文本：发送路径入库前会替换一级敏感词（MessageContentGuard），
 * 前端乐观气泡渲染这个版本而不是本地草稿——否则发送者自己看到原文、
 * 群友看到星号，同一句话两种内容。
 */
@Data
@Builder
public class GroupSendVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long msgId;

    private String content;
}
