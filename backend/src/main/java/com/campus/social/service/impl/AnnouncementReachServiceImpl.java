package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.Announcement;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AnnouncementMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AnnouncementReachService;
import com.campus.social.vo.AnnouncementRemindVO;
import com.campus.social.vo.UnreadUserPageVO;
import com.campus.social.vo.UnreadUserVO;
import com.campus.social.websocket.WsPushService;
import com.campus.social.websocket.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.campus.social.common.ResultCode.ANNOUNCEMENT_NOT_FOUND;

/**
 * 公告触达追踪实现（F5，AC-10）。
 *
 * <p>「推给谁」的三个集合语义各不相同，不能混：
 * <ul>
 *   <li>发布推送：在线的全体用户（不看是否已读——新公告本来就没人读过）</li>
 *   <li>提醒推送：在线<b>且未读</b>的用户（已读的人不该再被打扰）</li>
 *   <li>未读名单：全校未读用户（含离线，名单是给管理员线下找人用的）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementReachServiceImpl implements AnnouncementReachService {

    /** 同一管理员对同一条公告的提醒最小间隔，防止连点把 WS 推爆 */
    private static final Duration REMIND_COOLDOWN = Duration.ofSeconds(60);

    private final AnnouncementMapper announcementMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;
    private final WsSessionManager sessionManager;
    private final StringRedisTemplate redis;

    @Override
    public UnreadUserPageVO unreadUsers(long announcementId, Long schoolId, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int totalUnread = userMapper.countUnreadUsers(announcementId, schoolId);
        // cursor 从 0 起：未读名单按 id 升序，从旧到新看更符合「谁还没看」的排查习惯
        long from = cursor == null ? 0L : cursor;
        List<UnreadUserVO> users = userMapper.selectUnreadUsers(announcementId, schoolId, from, limit + 1);

        boolean hasMore = users.size() > limit;
        List<UnreadUserVO> page = hasMore ? users.subList(0, limit) : users;

        UnreadUserPageVO vo = new UnreadUserPageVO();
        vo.setList(page);
        vo.setHasMore(hasMore);
        vo.setTotalUnread(totalUnread);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        return vo;
    }

    @Override
    public AnnouncementRemindVO remind(long adminId, long announcementId, Long schoolId) {
        // 60 秒限频：管理员在手机上连点「提醒」很容易（网络慢时尤其）。
        // 不限频的后果不是「多点几下」，而是每次都遍历在线用户做集合差再广播一遍——
        // 一次误操作能把 WS 出口打满，受影响的是全校的聊天与通知推送。
        // 用 Redis setIfAbsent 而非本地 Map：多实例部署时本地限频各自为政，形同没有。
        String cooldownKey = "announce:remind:" + adminId + ":" + announcementId;
        Boolean first = redis.opsForValue().setIfAbsent(cooldownKey, "1", REMIND_COOLDOWN);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "提醒太频繁，请 " + REMIND_COOLDOWN.toSeconds() + " 秒后再试");
        }

        int totalUnread = userMapper.countUnreadUsers(announcementId, schoolId);
        if (totalUnread == 0) {
            // 全部已读时仍返回 200 + remindedCount=0。
            // 报错会让管理员以为系统坏了，而「没人未读」是正常状态。
            return AnnouncementRemindVO.builder().remindedCount(0).totalUnread(0).build();
        }

        Announcement ann = announcementMapper.selectById(announcementId);
        if (ann == null) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已删除");
        }

        // 提醒只推「在线且未读」的人：已读的人不该再被打扰
        Set<Long> online = sessionManager.onlineUserIds();
        Set<Long> readIds = new HashSet<>(announcementMapper.selectReadUserIds(announcementId));
        List<Long> targets = new ArrayList<>();
        for (Long uid : online) {
            if (!readIds.contains(uid)) {
                targets.add(uid);
            }
        }
        wsPush.pushAnnouncement(targets, announcementId, ann.getTitle(), ann.getCategory(), false);
        log.info("announcement remind: id={}, admin={}, online={}, totalUnread={}",
                announcementId, adminId, targets.size(), totalUnread);
        return AnnouncementRemindVO.builder()
                .remindedCount(targets.size())
                .totalUnread(totalUnread)
                .build();
    }
}