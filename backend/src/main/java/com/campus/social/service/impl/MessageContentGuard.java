package com.campus.social.service.impl;

import com.campus.social.exception.BusinessException;
import com.campus.social.service.AuditService;
import com.campus.social.util.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.campus.social.common.ResultCode.SENSITIVE_WORD_BLOCKED;

/**
 * 消息类内容的敏感词横切（2026-10-24 新增）。
 *
 * <p><b>由来</b>：此前聊天只拦 level-2（ChatGuard）且群聊**什么都不查**，
 * level-1 的处理更是在 ChatGuard 注释里声称「走替换」却从未实现——
 * 一级词在私信/群消息里原样收发。本组件把「消息类内容」的敏感词口径
 * 收成一处：level-2 拒发并审计，level-1 返回打码后的文本供入库。
 *
 * <p><b>为什么 level-1 直接打码而不送审</b>：消息没有审核队列可去
 * （review_task 是帖子专用，见 ChatGuardServiceImpl 的 AC-06 勘误），
 * 打码后入库与评论同口径；原文不留档是聊天隐私语义的一部分。
 *
 * <p>使用方：私聊 ChatServiceImpl.send、群聊 GroupServiceImpl.sendGroupMessage、
 * 集市 MktActivityServiceImpl（发布/编辑/留言）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageContentGuard {

    private final SensitiveWordFilter sensitive;
    private final AuditService auditService;

    /**
     * 检查并打码。
     *
     * @param senderId   发送者（审计归因）
     * @param scene      审计动作前缀：chat / group_chat / mkt
     * @param targetType 审计目标类型：chat_message / group_message / mkt_activity
     * @param content    原文
     * @return 打码后的文本（level-1 词替换为 *；无命中原样返回）
     * @throws BusinessException level-2 命中时拒发
     */
    public String sanitize(long senderId, String scene, String targetType, String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (sensitive.containsBlock(content)) {
            auditService.record(senderId, scene + "_block_sensitive", targetType, null,
                    "命中敏感词:" + sensitive.matchedLevel1Words(content), "");
            log.info("content blocked by sensitive word: sender={}, scene={}", senderId, scene);
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "内容含违规词，已拦截");
        }
        return sensitive.replaceAll(content);
    }
}
