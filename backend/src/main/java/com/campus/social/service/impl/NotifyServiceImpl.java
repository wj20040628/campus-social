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
        /*
         * 未读数口径统一(2026-10-23 修,审查 P1-13):
         * 旧写法是 selectCount 全量重算 → setUnreadNotifyCount → updateById 整行覆盖。
         * 两个问题:①读到的 unread 可能早于另一条 +1 提交,回写把它抹掉;
         * ②updateById 会把 follower_count 等无关计数一起写回,覆盖面过大。
         * 改为单语句原子重算:读和写之间没有窗口,也不碰其它列。
         */
        userMapper.recomputeUnreadNotifyCount(userId);
    }

    @Override
    @Transactional
    public void readAll(long userId) {
        notifyMapper.markAllRead(userId);
        // 无条件清零(2026-10-23 修,审查 P1-13):原先只在 affected>0 时清,
        // 而计数漂移(红点亮着但已无未读行)时 affected=0 → 红点永远清不掉。
        // markAllRead 本身幂等,清零同样幂等,不需要条件。
        userMapper.clearUnreadNotifyCount(userId);
        log.info("notify readAll: user={}", userId);
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
            case 13: return "你的文件已通过审核";
            case 14: return "你的文件未通过审核";
            case 15: return "你的文件已被下架";
            case 16: return "你的校园信息已登记";
            case 17: return "你的校园信息需要修改";
            // 举报与处置结果（2026-10-28）：18 举报人 / 19 封禁 / 20 内容下架 / 21 解封
            case 18: return "你举报的内容有结果了";
            case 19: return "你的账号已被暂停使用";
            case 20: return "你的内容已被下架";
            case 21: return "你的账号已恢复正常";
            case 22: return "你被邀请加入了群聊";
            case 23: return "你创建的圈子已被解散";
            default: return "";
        }
    }
}
