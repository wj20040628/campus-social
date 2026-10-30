package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;

import com.campus.social.entity.Relation;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.RelationService;
import com.campus.social.vo.RelationStatusVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
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
    private final PostNotifySupport postNotifySupport;

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
        // 通知被关注者:写 notify + 未读+1 + WS 实时推(同事务,与计数一致)
        postNotifySupport.pushNotify(toUserId, 1, toUserId, me, null);
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
    // addFriend 已于 2026-10-23 移除(审查 P0-4):单方面双向插行绕过陌生人搭话护栏,前端零调用。

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

    /**
     * 我拉黑的用户列表。与 following/followers 同一套游标分页:
     * relation 行 from_user_id=me 且 type=3(拉黑是单向的,只查我拉黑的,
     * "拉黑我的人"不属于这份列表 —— 那是对方的操作,我不该看到也不需要看到)。
     * to_user_id 才是被拉黑的人,组装走统一的脱敏 UserVoAssembler。
     */
    @Override
    public CursorPage<UserVO> blacklistList(long me, Long lastId, int size) {
        LambdaQueryWrapper<Relation> qw = new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromUserId, me)
                .eq(Relation::getType, TYPE_BLACK)
                .lt(lastId != null, Relation::getId, lastId)
                .orderByDesc(Relation::getId)
                .last("LIMIT " + (size + 1));
        List<Relation> rows = relationMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<UserVO> vos = rows.stream().limit(size)
                .map(r -> toUserVoById(r.getToUserId()))
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

    // insertFriend 已随 addFriend 一并移除(2026-10-23,审查 P0-4)

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
        return UserVoAssembler.of(userMapper.selectById(userId));
    }
}
