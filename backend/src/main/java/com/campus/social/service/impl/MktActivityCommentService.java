package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.MktComment;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.MktCommentMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.util.MktRateLimiter;
import com.campus.social.vo.MktCommentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 集市留言（2026-11-01 从 MktActivityServiceImpl 拆出）。
 *
 * <p>事务边界在 {@link MktActivityServiceImpl} 门面：插入留言与站内通知必须同进同退。
 * 本类只负责留言读写与展示字段填充。
 */
@Service
@RequiredArgsConstructor
class MktActivityCommentService {

    /** 站内通知类型：有人在我的集市活动下留言 → 通知发布者。 */
    private static final int MKT_COMMENT_NOTIFY_TYPE = 10;

    private final MktActivityMapper mktActivityMapper;
    private final MktCommentMapper mktCommentMapper;
    private final UserMapper userMapper;
    private final MessageContentGuard contentGuard;
    private final MktRateLimiter rateLimiter;
    private final PostNotifySupport postNotifySupport;

    void addComment(long activityId, long userId, MktCommentDTO dto) {
        MktActivity a = requireActivity(activityId);
        rateLimiter.guardComment(userId);
        // 与发布同口径:level-2 拒发,level-1 打码后入库
        String content = MktActivityRules.requireText(
                dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        String safeContent = contentGuard.sanitize(userId, "mkt", "mkt_activity", content);

        MktComment c = new MktComment();
        c.setActivityId(activityId);
        c.setUserId(userId);
        c.setContent(safeContent);
        mktCommentMapper.insert(c);

        // 复用 PostNotifySupport：写 notify + bump unread + WS 推。
        // 不通知自己（给自己留言无意义）；活动本身不在审核队列，故不涉及 type 6/7/8。
        if (!Objects.equals(a.getUserId(), userId)) {
            postNotifySupport.pushNotify(a.getUserId(), MKT_COMMENT_NOTIFY_TYPE, activityId, userId, null);
        }
    }

    CursorPage<MktCommentVO> listComments(long activityId, Long lastId, int size) {
        requireActivity(activityId);
        List<MktComment> rows = mktCommentMapper.selectByActivity(activityId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<MktComment> page = rows.stream().limit(size).toList();
        Map<Long, User> users = usersByIds(page.stream().map(MktComment::getUserId).toList());

        List<MktCommentVO> vos = page.stream().map(c -> {
            MktCommentVO vo = MktCommentVO.builder()
                    .id(c.getId())
                    .activityId(c.getActivityId())
                    .userId(c.getUserId())
                    .content(c.getContent())
                    .createTime(c.getCreateTime())
                    .build();
            User u = users.get(c.getUserId());
            if (u != null) {
                vo.setNickname(u.getNickname());
                vo.setAvatarUrl(u.getAvatarUrl());
            }
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /** 软删由 {@code selectById}（@TableLogic）自动过滤；返回 null 即 404。 */
    private MktActivity requireActivity(long activityId) {
        MktActivity a = mktActivityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
        return a;
    }

    private Map<Long, User> usersByIds(List<Long> ids) {
        Set<Long> uniq = new HashSet<>(ids);
        uniq.remove(null);
        if (uniq.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectByIds(uniq);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
