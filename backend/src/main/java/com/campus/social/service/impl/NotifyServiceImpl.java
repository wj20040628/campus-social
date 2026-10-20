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
import com.campus.social.assembler.UserVoAssembler;
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

    /**
     * 反馈回复(type=11)是「官方对个人」的通知,用户不该知道是哪个管理员处理的。
     * 匿名要在接口层做:只在前端隐藏,用户按 F12 看返回照样能查到管理员 id。
     */
    private static final int TYPE_FEEDBACK_REPLY = 11;

    private NotifyVO toVo(Notify n) {
        boolean anonymous = n.getType() != null && n.getType() == TYPE_FEEDBACK_REPLY;
        // 匿名时不查 user 表,顺带省一次查询;非匿名保持原逻辑
        UserVO actorVo = anonymous ? null : UserVoAssembler.of(userMapper.selectById(n.getActorId()));
        return NotifyVO.builder()
                .id(n.getId())
                .type(n.getType())
                .targetId(n.getTargetId())
                .postId(n.getPostId())
                .actorId(anonymous ? null : n.getActorId())
                .actor(actorVo)
                .summary(summaryFor(n))
                .content(n.getContent())
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
            case 12: return "你报名的活动已取消";
            case 7: return "你的内容已通过审核";
            case 8: return "你的内容未通过审核";
            case 9: return "在评论中@了你";
            case 10: return "在你的集市活动下留言了";
            case 11: return "回复了你的反馈";
            default: return "";
        }
    }
}
