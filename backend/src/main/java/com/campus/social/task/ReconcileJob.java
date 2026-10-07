package com.campus.social.task;

import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.ChatMessageMapper;
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
 *   post.like_count / comment_count / hot_score ← like / comment 表
 *   circle.member_count                       ← circle_member
 *   chat_conversation.unread_a/unread_b       ← chat_message(status=0, receiver侧)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CircleMapper circleMapper;
    private final ChatConversationMapper convMapper;
    private final ChatMessageMapper msgMapper;

    /** 每天凌晨 3 点全量对账 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcileAll() {
        long t0 = System.currentTimeMillis();
        log.info("[对账] 开始全量对账");

        int users = userMapper.reconcileAll();
        // 顺序依赖:先重算 comment_count(hot 公式 = like + 2*comment,需要最新 comment),
        // 再重算 hot_score,否则 hot 用的是旧 comment_count。
        int comments = postMapper.reconcileComments();
        int hot = postMapper.reconcileHotAndLikes();
        int circles = circleMapper.reconcileMemberCount();

        log.info("[对账] 完成: users={}, comments={}, hot/like={}, circles={}, 耗时={}ms",
                users, comments, hot, circles, System.currentTimeMillis() - t0);
    }

    /** 每 10 分钟:仅重算热度分(影响 Feed 排序,需较实时) */
    @Scheduled(cron = "0 */10 * * * ?")
    public void refreshHotScore() {
        int n = postMapper.reconcileHotAndLikes();
        if (n > 0) {
            log.debug("[对账] 热度分重算 {} 条", n);
        }
    }
}
