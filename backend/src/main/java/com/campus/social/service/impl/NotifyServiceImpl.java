package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.Notify;
import com.campus.social.entity.User;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.NotifyService;
import com.campus.social.vo.NotifyVO;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private final NotifyMapper notifyMapper;
    private final UserMapper userMapper;

    @Override
    public CursorPage<NotifyVO> list(long userId, Long lastId, int size) {
        LambdaQueryWrapper<Notify> qw = new LambdaQueryWrapper<Notify>()
                .eq(Notify::getUserId, userId)
                .lt(lastId != null, Notify::getId, lastId)
                .orderByDesc(Notify::getId)
                .last("LIMIT " + (size + 1));
        List<Notify> rows = notifyMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Notify> page = rows.stream().limit(size).toList();

        List<NotifyVO> vos = page.stream().map(this::toVo).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    @Transactional
    public void read(long notifyId, long userId) {
        notifyMapper.markRead(notifyId, userId);
        // 同步未读计数:重算(简单)
        Long unread = notifyMapper.selectCount(new LambdaQueryWrapper<Notify>()
                .eq(Notify::getUserId, userId)
                .eq(Notify::getIsRead, 0));
        User u = userMapper.selectById(userId);
        if (u != null) {
            u.setUnreadNotifyCount(unread.intValue());
            userMapper.updateById(u);
        }
    }

    @Override
    @Transactional
    public void readAll(long userId) {
        int affected = notifyMapper.markAllRead(userId);
        if (affected > 0) {
            userMapper.clearUnreadNotifyCount(userId);
        }
        log.info("notify readAll: user={}, affected={}", userId, affected);
    }

    @Override
    public int unreadCount(long userId) {
        User u = userMapper.selectById(userId);
        return u != null ? u.getUnreadNotifyCount() : 0;
    }

    private NotifyVO toVo(Notify n) {
        User actor = userMapper.selectById(n.getActorId());
        UserVO actorVo = actor == null ? null : UserVO.builder()
                .id(actor.getId())
                .username(actor.getUsername())
                .nickname(actor.getNickname() != null ? actor.getNickname() : actor.getUsername())
                .avatarUrl(actor.getAvatarUrl())
                .build();
        return NotifyVO.builder()
                .id(n.getId())
                .type(n.getType())
                .targetId(n.getTargetId())
                .postId(n.getPostId())
                .actorId(n.getActorId())
                .actor(actorVo)
                .summary(summaryFor(n))
                .isRead(n.getIsRead() == 1)
                .createTime(n.getCreateTime())
                .build();
    }

    private String summaryFor(Notify n) {
        switch (n.getType()) {
            case 1: return "关注了你";
            case 2: return "赞了你的内容";
            case 3: return "评论了你的内容";
            case 4: return "给你发了私信";
            case 5: return "报名了你创建的活动";
            case 6: return "你的内容正在审核中";
            case 7: return "你的内容已通过审核";
            case 8: return "你的内容未通过审核";
            case 9: return "在评论中@了你";
            default: return "";
        }
    }
}
