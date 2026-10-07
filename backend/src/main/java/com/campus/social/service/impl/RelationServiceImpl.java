package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;

import com.campus.social.entity.Relation;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.RelationService;
import com.campus.social.vo.RelationStatusVO;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RelationServiceImpl implements RelationService {

    /** 关系类型常量 */
    private static final int TYPE_FOLLOW = 1;
    private static final int TYPE_FRIEND = 2;
    private static final int TYPE_BLACK = 3;

    private final RelationMapper relationMapper;
    private final UserMapper userMapper;
    private final com.campus.social.service.AuditService auditService;

    // ---------------------------------------------------------------- 关注

    @Override
    @Transactional
    public void follow(long me, long toUserId) {
        if (me == toUserId) {
            throw new BusinessException(CHAT_SELF.getCode(), "不能关注自己");
        }
        // 幂等:已关注直接返回
        if (relationExists(me, toUserId, TYPE_FOLLOW)) {
            log.info("follow idempotent skip: {} -> {}", me, toUserId);
            return;
        }
        // 黑名单拦截
        if (relationExists(toUserId, me, TYPE_BLACK)) {
            throw new BusinessException(BLACKLISTED, "你被对方拉黑,无法关注");
        }
        Relation rel = new Relation();
        rel.setFromUserId(me);
        rel.setToUserId(toUserId);
        rel.setType(TYPE_FOLLOW);
        relationMapper.insert(rel);
        // 计数事务内 ±1
        userMapper.adjustFollowingCount(me, 1);
        userMapper.adjustFollowerCount(toUserId, 1);
        log.info("follow: {} -> {}", me, toUserId);
    }

    @Override
    @Transactional
    public void unfollow(long me, long toUserId) {
        Relation r = findRelation(me, toUserId, TYPE_FOLLOW);
        if (r == null) {
            // 幂等:未关注直接返回
            return;
        }
        relationMapper.hardDelete(me, toUserId, TYPE_FOLLOW);
        userMapper.adjustFollowingCount(me, -1);
        userMapper.adjustFollowerCount(toUserId, -1);
        log.info("unfollow: {} -> {}", me, toUserId);
    }

    // ---------------------------------------------------------------- 好友

    @Override
    @Transactional
    public void addFriend(long me, long toUserId) {
        if (me == toUserId) {
            throw new BusinessException(CHAT_SELF.getCode(), "不能加自己为好友");
        }
        // 双向各存一行
        insertFriend(me, toUserId);
        insertFriend(toUserId, me);
        userMapper.adjustFollowerCount(me, 0); // 计数不变(好友不增加 follower_count)
    }

    @Override
    @Transactional
    public void removeFriend(long me, long toUserId) {
        Relation r1 = findRelation(me, toUserId, TYPE_FRIEND);
        if (r1 != null)
            relationMapper.hardDelete(me, toUserId, TYPE_FRIEND);
        Relation r2 = findRelation(toUserId, me, TYPE_FRIEND);
        if (r2 != null)
            relationMapper.hardDelete(toUserId, me, TYPE_FRIEND);
    }

    @Override
    @Transactional
    public void blacklist(long me, long toUserId) {
        if (me == toUserId) {
            throw new BusinessException(CHAT_SELF.getCode(), "不能拉黑自己");
        }
        insertRelation(me, toUserId, TYPE_BLACK);
        // 取关互相关系
        unfollow(me, toUserId);
        unfollow(toUserId, me);
        // 审计:拉黑
        auditService.record(me, "blacklist", "relation", toUserId, "拉黑用户 " + toUserId, null);
        log.info("blacklist: {} -> {}", me, toUserId);
    }

    @Override
    @Transactional
    public void unblacklist(long me, long toUserId) {
        Relation r = findRelation(me, toUserId, TYPE_BLACK);
        if (r != null) {
            relationMapper.hardDelete(me, toUserId, TYPE_BLACK);
        }
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public RelationStatusVO status(long me, long otherId) {
        boolean following = relationExists(me, otherId, TYPE_FOLLOW);
        boolean followedBy = relationExists(otherId, me, TYPE_FOLLOW);
        boolean friend = relationExists(me, otherId, TYPE_FRIEND)
                && relationExists(otherId, me, TYPE_FRIEND);
        boolean blacklisted = relationExists(me, otherId, TYPE_BLACK);
        return RelationStatusVO.builder()
                .following(following)
                .followedBy(followedBy)
                .friend(friend)
                .blacklisted(blacklisted)
                .build();
    }

    @Override
    public CursorPage<UserVO> following(long me, Long lastId, int size) {
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromUserId, me)
                .eq(Relation::getType, TYPE_FOLLOW)
                .lt(lastId != null, Relation::getId, lastId)
                .orderByDesc(Relation::getId)
                .last("LIMIT " + (size + 1));
        List<Relation> rows = relationMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        // 我关注的:每个 Relation 的 toUserId 才是"被关注的人"
        List<UserVO> vos = rows.stream().limit(size)
                .map(r -> toUserVoById(r.getToUserId()))
                .filter(Objects::nonNull)
                .toList();
        Long nextCursor = hasMore ? rows.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<UserVO> followers(long me, Long lastId, int size) {
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getToUserId, me)
                .eq(Relation::getType, TYPE_FOLLOW)
                .lt(lastId != null, Relation::getId, lastId)
                .orderByDesc(Relation::getId)
                .last("LIMIT " + (size + 1));
        List<Relation> rows = relationMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        // 我的粉丝:每个 Relation 的 fromUserId 才是"关注我的人"
        List<UserVO> vos = rows.stream().limit(size)
                .map(r -> toUserVoById(r.getFromUserId()))
                .filter(Objects::nonNull)
                .toList();
        Long nextCursor = hasMore ? rows.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    // ---------------------------------------------------------------- 私有

    private void insertRelation(long from, long to, int type) {
        if (relationExists(from, to, type))
            return;
        Relation rel = new Relation();
        rel.setFromUserId(from);
        rel.setToUserId(to);
        rel.setType(type);
        relationMapper.insert(rel);
    }

    private void insertFriend(long from, long to) {
        if (relationExists(from, to, TYPE_FRIEND))
            return;
        Relation rel = new Relation();
        rel.setFromUserId(from);
        rel.setToUserId(to);
        rel.setType(TYPE_FRIEND);
        relationMapper.insert(rel);
    }

    private boolean relationExists(long from, long to, int type) {
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromUserId, from)
                .eq(Relation::getToUserId, to)
                .eq(Relation::getType, type);
        return relationMapper.selectCount(qw) > 0;
    }

    private Relation findRelation(long from, long to, int type) {
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromUserId, from)
                .eq(Relation::getToUserId, to)
                .eq(Relation::getType, type);
        List<Relation> list = relationMapper.selectList(qw);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 userId 组装脱敏 UserVO(用户被删返回 null,调用方 filter) */
    private UserVO toUserVoById(Long userId) {
        if (userId == null)
            return null;
        User u = userMapper.selectById(userId);
        if (u == null)
            return null;
        return UserVO.builder()
                .id(u.getId())
                .username(u.getUsername())
                .nickname(u.getNickname() != null ? u.getNickname() : u.getUsername())
                .avatarUrl(u.getAvatarUrl())
                .grade(u.getGrade())
                .dept(u.getDept())
                .followerCount(u.getFollowerCount())
                .followingCount(u.getFollowingCount())
                .build();
    }
}
