package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.ChatGuardService;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.service.support.StrangerPolicy;
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

/**
 * 搭话护栏实现（F3）。
 *
 * <p>判定顺序（2026-10-30 调整）：
 * <ol>
 *   <li>不能给自己发——纯内存比较，不查库</li>
 *   <li>发送方存在且未冻结——查 user</li>
 *   <li>双向黑名单——查 relation，命中任一方向即拒</li>
 *   <li>接收方存在 + 陌生人开关——陌生人判定见 {@link StrangerPolicy}</li>
 *   <li>仅对陌生人检查行为门槛：账号满 24 小时 + 信誉 60；
 *       回复已有会话/互关不设门槛</li>
 *   <li>敏感词——DFA 扫描，<b>必须在配额之前</b>：被拒的内容不该消耗当天的 1 条</li>
 *   <li>陌生人日配额——Redis 往返，只有真正会落库的消息才扣</li>
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
    private final StrangerPolicy strangerPolicy;
    private final SensitiveWordFilter sensitiveFilter;
    private final AuditService auditService;
    /** 行为信任门槛：账号满 24 小时 + 信誉 60 才能主动搭话 */
    private final CampusAccessService access;

    @Override
    public ChatSendVO check(long me, long peerId, String content) {
        // 1. 不能给自己发。最便宜的一道，放最前。
        if (me == peerId) {
            throw new BusinessException(CHAT_SELF, "不能给自己发消息");
        }

        // 2. 发送方存在且未被冻结。校园信息不参与判定（2026-10-30）：
        //    回复已有会话、互关后的对话都不该被一条不可核验的资料挡住。
        User meUser = userMapper.selectById(me);
        if (meUser == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发送方不存在");
        }
        if (Integer.valueOf(1).equals(meUser.getStatus())) {
            throw new BusinessException(USER_FROZEN, "账号已冻结，无法发送消息");
        }

        // 3. 双向黑名单。拉黑是单向动作，只查一个方向等于没护栏。
        if (relationMapper.countBlacklistEitherDirection(me, peerId) > 0) {
            throw new BusinessException(BLACKLISTED, "你与对方之间存在黑名单关系，无法发送消息");
        }

        // 4. 接收方开关（AC-05）。只对真正的陌生人有效——
        //    对方已经关注我/回复过我时，他已经主动开了门，不该被自己的开关挡住。
        User peer = userMapper.selectById(peerId);
        if (peer == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "对方不存在");
        }
        boolean isStranger = strangerPolicy.isStranger(me, peerId);
        if (isStranger && !Integer.valueOf(1).equals(peer.getStrangerMsgEnabled())) {
            throw new BusinessException(STRANGER_MSG_CLOSED, "对方已关闭陌生人消息，无法发送");
        }
        /*
         * 只有「主动找陌生人」才需要行为门槛（2026-10-30）。
         * 已有会话 / 互关 / 对方关注我 / 对方回复过我 → isStranger=false，
         * 直接放行；回复别人不该被账号年龄卡住。
         */
        if (isStranger) {
            access.requireStrangerChat(meUser, "发起搭话");
        }

        /*
         * 5. 敏感词（AC-06）。level=2 命中即拒发；level=1 由发送路径入库前打码。
         *
         * 放在配额之前（2026-10-28）：此前先扣额度再查词，一条被拒的脏话
         * 会吃掉用户当天唯一的一条搭话机会。DFA 比 Redis 便宜，也更该先跑。
         */
        if (sensitiveFilter.containsBlock(content)) {
            List<String> hits = sensitiveFilter.matchedLevel1Words(content);
            auditService.record(me, ACTION_BLOCK_SENSITIVE, "chat_message", null,
                    "命中敏感词:" + hits, "");
            log.info("chat blocked by sensitive word: sender={}, peer={}, hits={}", me, peerId, hits);
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "内容含违规词，已拦截");
        }

        // 6. 配额（AC-04）。只有陌生人且消息会落库时才扣；对方关注/回复后不限制。
        Integer remaining = isStranger ? quotaService.consumePerPeer(me, peerId) : null;

        return ChatSendVO.builder()
                .isStranger(isStranger)
                .remainingPerPeerQuota(remaining)
                .build();
    }
}
