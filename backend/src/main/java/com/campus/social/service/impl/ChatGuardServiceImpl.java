package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.ChatGuardService;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.ChatSendVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.campus.social.common.ResultCode.BLACKLISTED;
import static com.campus.social.common.ResultCode.CHAT_SELF;
import static com.campus.social.common.ResultCode.SENSITIVE_WORD_BLOCKED;
import static com.campus.social.common.ResultCode.STRANGER_MSG_CLOSED;
import static com.campus.social.common.ResultCode.USER_FROZEN;
import static com.campus.social.common.ResultCode.USER_NOT_VERIFIED;

/**
 * 搭话护栏实现（F3）。
 *
 * <p>六道判定，顺序即成本序（openapi.yaml:272-278）：
 * <ol>
 *   <li>不能给自己发——纯内存比较，不查库</li>
 *   <li>发送方未通过校内认证——查 user，AC-11</li>
 *   <li>双向黑名单——查 relation，命中任一方向即拒</li>
 *   <li>接收方 stranger_msg_enabled=0——已查到的 user 对象上直接读字段</li>
 *   <li>陌生人日配额——Redis 往返，前四道过了才走</li>
 *   <li>敏感词——DFA 全串扫描，最贵，放最后</li>
 * </ol>
 *
 * <p><b>AC-06 的勘误</b>：SPEC AC-06 与 openapi:278 都写「写入 review_task」，
 * 但 {@code review_task} 是帖子专用队列（{@code post_id BIGINT NOT NULL}），
 * 聊天消息不是帖子，插不进去。此处按 ARCHITECTURE §3 Q9.4 的裁决改写
 * {@code audit_log}（多态、只增不删），action = {@code chat_block_sensitive}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatGuardServiceImpl implements ChatGuardService {

    /** 审计动作名：搭话被敏感词拦截 */
    private static final String ACTION_BLOCK_SENSITIVE = "chat_block_sensitive";

    private final UserMapper userMapper;
    private final RelationMapper relationMapper;
    private final StrangerQuotaService quotaService;
    private final SensitiveWordFilter sensitiveFilter;
    private final AuditService auditService;

    @Override
    public ChatSendVO check(long me, long peerId, String content) {
        // 1. 不能给自己发。最便宜的一道，放最前。
        if (me == peerId) {
            throw new BusinessException(CHAT_SELF, "不能给自己发消息");
        }

        // 2. 发送方须已通过校内认证（AC-11）。顺带拦掉冻结账号——
        //    已冻结用户不该还能对外发消息，但 status 与 verifyStatus 是两列，要分开判。
        User meUser = userMapper.selectById(me);
        if (meUser == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发送方不存在");
        }
        if (Integer.valueOf(1).equals(meUser.getStatus())) {
            throw new BusinessException(USER_FROZEN, "账号已冻结，无法发送消息");
        }
        if (!Integer.valueOf(1).equals(meUser.getVerifyStatus())) {
            throw new BusinessException(USER_NOT_VERIFIED, "完成校内认证后才能发起搭话");
        }

        // 3. 双向黑名单。拉黑是单向动作，只查一个方向等于没护栏。
        if (relationMapper.countBlacklistEitherDirection(me, peerId) > 0) {
            throw new BusinessException(BLACKLISTED, "你与对方之间存在黑名单关系，无法发送消息");
        }

        // 4. 接收方开关（AC-05）。这一道只对陌生人生效——
        //    已是好友/已关注的人不受 stranger_msg_enabled 约束，
        //    否则用户关了「陌生人搭话」会连正常聊天也一起关掉。
        User peer = userMapper.selectById(peerId);
        if (peer == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "对方不存在");
        }
        boolean isStranger = relationMapper.countFollowOrFriendEitherDirection(me, peerId) == 0;
        if (isStranger && !Integer.valueOf(1).equals(peer.getStrangerMsgEnabled())) {
            throw new BusinessException(STRANGER_MSG_CLOSED, "对方已关闭陌生人消息，无法发送");
        }

        // 5. 配额（AC-04）。新对象与同一对象是两个独立计数器，由
        //    consumeStrangerQuota 一并处理——分开调用会出现「扣了新的没扣旧的」
        //    或反之的半扣状态，而 ZSET 无法安全回退。
        Integer remaining = isStranger ? quotaService.consumeStrangerQuota(me, peerId) : null;

        // 6. 敏感词（AC-06）。level=2 命中即拒发。
        //    level=1 由发送路径入库前打码（ChatServiceImpl.send → MessageContentGuard.sanitize），
        //    本护栏只负责「拒」，替换是「放行前的改写」，两件事在两个环节。
        //    【勘误史】此注释曾写「level=1 走替换不在此拦」，但替换从未实现——
        //    一级词在私信原样收发半年，2026-10-24 才补上（同日补群聊零检查）。
        if (sensitiveFilter.containsBlock(content)) {
            List<String> hits = sensitiveFilter.matchedLevel1Words(content);
            auditService.record(me, ACTION_BLOCK_SENSITIVE, "chat_message", null,
                    "命中敏感词:" + hits, "");
            log.info("chat blocked by sensitive word: sender={}, peer={}, hits={}", me, peerId, hits);
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "内容含违规词，已拦截");
        }

        return ChatSendVO.builder().isStranger(isStranger).remainingNewPeerQuota(remaining).build();
    }
}