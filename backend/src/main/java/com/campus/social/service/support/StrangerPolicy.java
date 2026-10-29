package com.campus.social.service.support;

import com.campus.social.mapper.ChatMessageMapper;
import com.campus.social.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 陌生人判定（2026-10-28 重做）。
 *
 * <p><b>什么算“不再是陌生人”</b>：
 * <ul>
 *   <li>对方关注了我（peer → me 的 type=1 或 type=2）——对方主动开门；</li>
 *   <li>对方在本会话回复过我（一条未撤回的私信）——行为上的同意；</li>
 * </ul>
 *
 * <p><b>我关注对方不算</b>。此前用 {@code countFollowOrFriendEitherDirection}
 * 判任一方向，导致“关注”成为免费解锁开关：认证用户只要关注 3 个人，
 * 就能在当天给这 3 个人无限发消息。关注是单向动作，不能替对方表达意愿。
 *
 * <p>好友（type=2）在本项目里由双向行表示；只要 peer → me 方向存在，
 * 就足以说明对方这边已经建立了关系，放行。
 */
@Component
@RequiredArgsConstructor
public class StrangerPolicy {

    private final RelationMapper relationMapper;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * true = 仍是陌生人，需要接收方开关 + 每日额度约束。
     */
    public boolean isStranger(long me, long peerId) {
        return !peerOptedIn(me, peerId);
    }

    /**
     * 对方是否已用「关注我」或「回复我」表达了继续聊的意愿。
     */
    public boolean peerOptedIn(long me, long peerId) {
        return relationMapper.countPeerSideRelation(me, peerId) > 0
                || chatMessageMapper.existsReplyFromPeer(me, peerId);
    }
}
