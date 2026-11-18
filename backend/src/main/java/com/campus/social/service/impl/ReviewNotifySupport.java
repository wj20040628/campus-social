package com.campus.social.service.impl;

import com.campus.social.entity.Notify;
import com.campus.social.entity.Post;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 审核结果通知作者（2026-11-14 从 ReviewServiceImpl 拆出）。
 *
 * <p>type=7 通过 / 8 驳回，targetId=postId（跳详情），actorId=审核员。
 * 站内通知落库、未读数加一、事务提交后推 WebSocket 三件事必须同序执行，
 * 集中在一个出口避免通过/驳回两条路径各写一份。
 */
@Service
@RequiredArgsConstructor
public class ReviewNotifySupport {

    /** notify.content 是 VARCHAR(500)，理由最多 512，入库前截断避免整个驳回事务失败 */
    private static final int NOTIFY_CONTENT_MAX = 500;

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;

    public void pushResult(long authorId, int type, Post post, long reviewerId, String reason) {
        Notify n = new Notify();
        n.setUserId(authorId);
        n.setType(type);
        n.setTargetId(post.getId());
        n.setPostId(post.getId());
        n.setActorId(reviewerId);
        n.setContent(truncate(reason));
        n.setIsRead(0);
        notifyMapper.insert(n);
        userMapper.adjustUnreadNotifyCount(authorId, 1);
        wsAfterCommit.run(() -> wsPush.pushNotify(authorId, n.getId(), type, post.getId(), reviewerId));
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > NOTIFY_CONTENT_MAX
                ? reason.substring(0, NOTIFY_CONTENT_MAX) : reason;
    }
}