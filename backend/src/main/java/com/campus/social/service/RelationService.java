package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.RelationStatusVO;
import com.campus.social.vo.UserVO;

/**
 * 关系:关注/好友/黑名单
 * 计数全部在事务内 ±1 user.follower_count / following_count,绝不读改写
 */
public interface RelationService {

    /** 关注 toUserId:插入 relation(type=1) + 双方计数 ±1,幂等(重复返 200) */
    void follow(long me, long toUserId);

    void unfollow(long me, long toUserId);

    /**
     * 加好友已于 2026-10-23 移除(审查 P0-4):单方面成立可绕过陌生人搭话护栏,且前端零调用。
     * 好友关系如需恢复,必须做成申请-同意两阶段。
     */
    void removeFriend(long me, long toUserId);

    void blacklist(long me, long toUserId);

    void unblacklist(long me, long toUserId);

    /** 当前关系状态 */
    RelationStatusVO status(long me, long otherId);

    /** 我关注的列表(游标 lastId) */
    CursorPage<UserVO> following(long me, Long lastId, int size);

    /** 粉丝列表 */
    CursorPage<UserVO> followers(long me, Long lastId, int size);

    /** 我拉黑的用户列表(游标 lastId):设置页黑名单管理用 */
    CursorPage<UserVO> blacklistList(long me, Long lastId, int size);
}
