package com.campus.social.service.impl;

import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.ChatConvPrefMapper;
import com.campus.social.mapper.ChatMessageMapper;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.CancelledAccountRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销留存清理实现（2026-11-08）。
 *
 * <p>物理删除而非软删：留存期结束后的数据不应继续占库，
 * 软删只是把「删除」变成了「藏起来」，与隐私政策里的清理承诺不符。
 *
 * <p>只清私信/会话偏好/通知三类「通信与提醒」数据；
 * 帖子、评论、举报、审计日志是内容留痕与监管记录，保留并已去标识化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelledAccountRetentionServiceImpl implements CancelledAccountRetentionService {

    private final NotifyMapper notifyMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConvPrefMapper chatConvPrefMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public PurgeResult purgeUser(long userId) {
        int notifications = notifyMapper.hardDeleteByUser(userId);
        int messages = chatMessageMapper.hardDeleteByUser(userId);
        int prefs = chatConvPrefMapper.hardDeleteByUser(userId);
        int conversations = chatConversationMapper.hardDeleteByUser(userId);
        userMapper.markPurged(userId);
        PurgeResult result = new PurgeResult(notifications, messages, prefs, conversations);
        log.info("cancelled account retention purge: uid={}, notifications={}, messages={}, prefs={}, conversations={}",
                userId, notifications, messages, prefs, conversations);
        return result;
    }
}
