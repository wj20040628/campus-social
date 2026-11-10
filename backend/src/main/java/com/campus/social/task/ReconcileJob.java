package com.campus.social.task;

import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据对账 Job:冗余计数列与真实数据定期对账,纠偏差漂移
 *
 * 触发:每天凌晨 3 点全量跑一次(校园数据量小,全量重算代价可接受)
 *
 * 对账对象(冗余列 → 真实来源):
 *   user.follower_count / following_count   ← relation(type=1)
 *   user.unread_notify_count                 ← notify(is_read=0)
 *   user.joined_circle_count                 ← circle_member(有效圈)
 *   post.like_count / comment_count / hot_score ← like / comment 表
 *   circle.member_count                       ← circle_member
 *   group.member_count                        ← group_member
 *   activity.current_count                     ← activity_signup
 *   chat_conversation.unread_a/unread_b       ← chat_message(status=0, receiver侧)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CircleMapper circleMapper;
    private final GroupMapper groupMapper;
    private final ActivityMapper activityMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final MeterRegistry meterRegistry;

    /** 每天凌晨 3 点全量对账 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcileAll() {
        long t0 = System.currentTimeMillis();
        log.info("[对账] 开始全量对账");

        int groupDrift = groupMapper.countMemberCountDrift();
        int activityDrift = activityMapper.countCurrentCountDrift();
        int joinedDrift = userMapper.countJoinedCircleDrift();
        int chatDrift = chatConversationMapper.countUnreadDrift();
        int notifyDrift = userMapper.countUnreadNotifyDrift();
        warnDrift("group_member_count", groupDrift);
        warnDrift("activity_current_count", activityDrift);
        warnDrift("user_joined_circle_count", joinedDrift);
        warnDrift("chat_conversation_unread", chatDrift);
        warnDrift("user_unread_notify_count", notifyDrift);

        int users = userMapper.reconcileAll();
        int unreadNotify = userMapper.reconcileUnreadNotifyCount();
        int joined = userMapper.reconcileJoinedCircleCount();
        // 顺序依赖:先重算 comment_count(hot 公式 = like + 2*comment,需要最新 comment),
        // 再重算 hot_score,否则 hot 用的是旧 comment_count。
        int comments = postMapper.reconcileComments();
        int hot = postMapper.reconcileHotAndLikes();
        int circles = circleMapper.reconcileMemberCount();
        int groups = groupMapper.reconcileMemberCount();
        int activities = activityMapper.reconcileCurrentCount();
        int chats = chatConversationMapper.reconcileUnread();

        log.info("[对账] 完成: users={}, unreadNotify={}, joinedCircles={}, comments={}, hot/like={}, circles={}, "
                        + "groups={}, activities={}, chatUnread={}, 耗时={}ms",
                users, unreadNotify, joined, comments, hot, circles, groups, activities, chats,
                System.currentTimeMillis() - t0);
    }

    /** 漂移只告警不阻断:对账随后会修正,指标用于发现新漏路径 */
    private void warnDrift(String type, int drift) {
        if (drift > 0) {
            log.warn("[对账] 发现漂移 type={} count={}", type, drift);
            meterRegistry.counter("campus.data.drift", "type", type).increment(drift);
        }
    }

    /**
     * 每 10 分钟:仅重算最近 7 天帖子的热度分。
     *
     * <p>为什么不刷全表:hot_score 是时间衰减函数,发布 7 天以上的帖子
     * 无论重算多少次都排在热门榜之外,给它 10 分钟精度没有意义,
     * 却要付出「全表 UPDATE + 每行一次 like 计数」的代价。
     *
     * <p>老帖的 like_count 若因异常漂移,由每天 3 点的全量对账修正。
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void refreshHotScore() {
        int n = postMapper.refreshRecentHotScore();
        if (n > 0) {
            log.debug("[对账] 近 7 天热度分重算 {} 条", n);
        }
    }
}
