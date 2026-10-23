package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.ChatConvPref;
import com.campus.social.entity.ChatConversation;
import com.campus.social.entity.Group;
import com.campus.social.mapper.ChatConvPrefMapper;
import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.service.ChatConvPrefService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConvPrefServiceImpl implements ChatConvPrefService {

    private final ChatConvPrefMapper prefMapper;
    private final ChatConversationMapper convMapper;
    private final GroupMapper groupMapper;

    @Override
    public Map<Long, ChatConvPref> loadByKind(long me, String kind) {
        List<ChatConvPref> rows = prefMapper.selectByUserAndKind(me, kind);
        return rows.stream().collect(Collectors.toMap(ChatConvPref::getTargetId, Function.identity(), (a, b) -> a));
    }

    @Override
    public void set(long me, String kind, long targetId, Boolean pinned, Boolean muted, Boolean hidden) {
        if (pinned == null && muted == null && hidden == null) {
            return;
        }
        ChatConvPref pref = prefMapper.selectOne(me, kind, targetId);
        if (pref == null) {
            pref = new ChatConvPref();
            pref.setUserId(me);
            pref.setKind(kind);
            pref.setTargetId(targetId);
            pref.setPinned(0);
            pref.setMuted(0);
            pref.setHidden(0);
            pref.setHiddenUpto(0L);
            applyFlags(pref, kind, targetId, pinned, muted, hidden);
            prefMapper.insert(pref);
            return;
        }
        applyFlags(pref, kind, targetId, pinned, muted, hidden);
        prefMapper.updateById(pref);
    }

    @Override
    public boolean isMuted(long me, String kind, long targetId) {
        ChatConvPref pref = prefMapper.selectOne(me, kind, targetId);
        return pref != null && pref.getMuted() != null && pref.getMuted() == 1;
    }

    /**
     * 把三个可空开关落到实体上。
     *
     * <p>hidden 走 hidden_upto：只有在「从 0 变 1」的那一刻才记录当时的最后消息 id。
     * 重复删（已经 hidden）不覆盖，否则第二次删会把水位抬到最新消息上，
     * 把「删完之后刚收到的新消息」连带一起藏掉。
     */
    private void applyFlags(ChatConvPref pref, String kind, long targetId,
                            Boolean pinned, Boolean muted, Boolean hidden) {
        if (pinned != null) {
            pref.setPinned(pinned ? 1 : 0);
        }
        if (muted != null) {
            pref.setMuted(muted ? 1 : 0);
        }
        if (hidden != null) {
            boolean wasHidden = pref.getHidden() != null && pref.getHidden() == 1;
            if (hidden && !wasHidden) {
                pref.setHiddenUpto(currentLastMsgId(kind, pref.getUserId(), targetId));
            }
            if (!hidden) {
                pref.setHiddenUpto(0L);
            }
            pref.setHidden(hidden ? 1 : 0);
        }
    }

    /** 该会话"当前"最后一条消息 id：私信查会话行，群聊查群卡片 */
    private long currentLastMsgId(String kind, long me, long targetId) {
        Long lastId = null;
        if (ChatConvPref.KIND_GROUP.equals(kind)) {
            Group g = groupMapper.selectById(targetId);
            lastId = g == null ? null : g.getLastMsgId();
        } else {
            long a = Math.min(me, targetId);
            long b = Math.max(me, targetId);
            // 双向查：历史脏数据里可能存在 (member_a > member_b) 的反序行
            LambdaQueryWrapper<ChatConversation> qw = new LambdaQueryWrapper<>();
            qw.and(w -> w
                            .nested(x -> x.eq(ChatConversation::getMemberA, a).eq(ChatConversation::getMemberB, b))
                            .or()
                            .nested(x -> x.eq(ChatConversation::getMemberA, b).eq(ChatConversation::getMemberB, a)))
                    .orderByAsc(ChatConversation::getId)
                    .last("LIMIT 1");
            ChatConversation conv = convMapper.selectOne(qw);
            lastId = conv == null ? null : conv.getLastMsgId();
        }
        return lastId == null ? 0L : lastId;
    }
}
