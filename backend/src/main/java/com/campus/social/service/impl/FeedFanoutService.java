package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Relation;
import com.campus.social.mapper.FeedItemMapper;
import com.campus.social.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feed 写扩散。
 *
 * <p><b>为什么独立</b>：写扩散有三个触发点——发帖即通过、编辑后仍通过、审核通过后补扩散，
 * 逻辑完全相同。放在一起时改「扩散失败要不要回滚帖子」要同时改三处。
 *
 * <p><b>关键设计：单条失败不阻断整批</b>。扩散是「锦上添花」而非数据正确性的一部分——
 * feed_item 缺失时 Feed 查询会降级到时间线，用户仍能看到帖子。
 * 反过来若因一条脏数据让整个发帖事务回滚，就是用可选功能卡死了核心功能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedFanoutService {

    private static final int BATCH_SIZE = 500;

    private final FeedItemMapper feedItemMapper;
    private final RelationMapper relationMapper;
    /** 首页首屏缓存：帖子进入 Feed 就要让它失效 */
    private final FeedPageCacheService feedCache;

    /**
     * 查 author 的粉丝并批量写入 feed_item 收件箱。
     *
     * <p>分批 subList 而非一次性 selectList 全量入内存：粉丝数不可控，
     * 大V 场景下一次性加载会占满堆。
     *
     * <p><b>顺带失效首页缓存</b>：三个「帖子进入 Feed」的入口（发帖即通过、
     * 编辑后仍通过、审核通过后补扩散）都汇到这里，是唯一不用重复写三遍的位置。
     * 放在方法开头是刻意的——粉丝为空的作者会提前 return，
     * 但帖子照样进了全站首页，缓存也必须失效。
     */
    public void fanout(long authorId, long postId) {
        feedCache.invalidate();
        // 我关注的人，即「粉丝」是那些 to_user_id=authorId 的 from_user_id
        List<Relation> fans = relationMapper.selectList(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getToUserId, authorId)
                .eq(Relation::getType, 1)); // type=1 关注
        if (fans.isEmpty()) {
            return;
        }

        int total = fans.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<Relation> batch = fans.subList(i, Math.min(i + BATCH_SIZE, total));
            for (Relation fan : batch) {
                try {
                    // insertIgnore:依赖 uk_fan_post 唯一键幂等(审查 P0-7),
                    // 审核并发通过等场景下的重复扩散在这里静默去重
                    feedItemMapper.insertIgnore(fan.getFromUserId(), authorId, postId);
                } catch (Exception e) {
                    // 单条失败不影响其他,记日志(后续接 MQ 死信)
                    log.warn("feed fanout fail: fan={}, post={}, err={}",
                            fan.getFromUserId(), postId, e.getMessage());
                }
            }
        }
        log.info("feed fanout: post={}, fans={}", postId, total);
    }
}
