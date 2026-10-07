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

    /** 加好友:双向两行(type=2) */
    void addFriend(long me, long toUserId);

    void removeFriend(long me, long toUserId);

    void blacklist(long me, long toUserId);

    void unblacklist(long me, long toUserId);

    /** 当前关系状态 */
    RelationStatusVO status(long me, long otherId);

    /** 我关注的列表(游标 lastId) */
    CursorPage<UserVO> following(long me, Long lastId, int size);

    /** 粉丝列表 */
    CursorPage<UserVO> followers(long me, Long lastId, int size);
}
