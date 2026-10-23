package com.campus.social.service.impl;

import com.campus.social.vo.PostVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 首页 Feed 第一页的 Redis 缓存。
 *
 * <p><b>缓存的是「公共骨架」，不是整页响应</b>。这是本类最要紧的一条约束：
 * 一条帖子对所有人显示的作者、话题、圈名、敏感词清洗后的正文都相同，
 * 但「我有没有点赞/收藏」是因人而异的。所以缓存里只存 viewerId=0 装配出的骨架
 * （liked/favorited 恒为 false），每次命中后再由
 * {@link PostVoAssembler#fillViewerState} 用两次 IN 查询回填当前用户的状态。
 *
 * <p>反例（千万不要这么改）：把带 liked=true 的 VO 直接写进缓存，
 * 下一个打开首页的人会看到「自己已经赞过一堆没赞过的帖子」。
 *
 * <p><b>为什么只缓存第一页</b>：首屏是唯一被所有人反复请求的页。
 * 第二页以后每个用户翻到的时间点不同、内容也不一致，命中率极低，
 * 缓存它们只是把内存花在噪声上。
 *
 * <p><b>失效策略：版本号 + TTL 双保险</b>。键名里带一个版本号，
 * 「失效」不是去删一堆键（Redis 5.0 上 KEYS 是禁用命令、SCAN 又慢），
 * 而是把版本号 +1，所有旧键立刻变成没人访问的孤儿，60 秒后自然过期。
 * 所以发帖、删帖、下架这类「内容变了」的动作只需调一次 {@link #invalidate()}。
 *
 * <p><b>Redis 不可用时回落查库</b>，不抛错。缓存是加速手段，不是数据源，
 * 让它故障导致首页打不开是把加速手段变成了单点故障（与 DictServiceImpl 同一口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedPageCacheService {

    /** 缓存存活秒数。60 秒是新帖「最晚多久能被看见」的上界（正常靠失效立即生效）。 */
    private static final int TTL_SECONDS = 60;

    /** 版本号键。递增它等于让所有已缓存的页面立刻失效。 */
    private static final String VERSION_KEY = "feed:p1:ver";

    /** 页面键前缀，完整形态：feed:p1:v3:time:desc:20 */
    private static final String PAGE_PREFIX = "feed:p1";

    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    /** 缓存的页面快照：列表本身 + 游标信息（游标不缓存的话，命中缓存就没法继续翻页） */
    @Data
    public static class Snapshot {
        private List<PostVO> items;
        private Long nextCursor;
        private boolean hasMore;
    }

    /**
     * 读缓存。未命中或 Redis 异常一律返回 null，由调用方回落查库。
     */
    public Snapshot read(String by, String order, int size) {
        try {
            String key = buildKey(by, order, size);
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return om.readValue(json, Snapshot.class);
        } catch (Exception e) {
            // 反序列化失败（比如 VO 字段改过、旧格式残留）同样回落查库，
            // 不能因为一条脏缓存让首页 500
            log.warn("feed page cache read failed, fallback to db. cause={}", e.toString());
            return null;
        }
    }

    /**
     * 写缓存。
     *
     * <p><b>传进来的必须是公共骨架</b>：调用方要在回填用户态「之前」调用本方法。
     * 顺序反了会把某个用户的点赞状态广播给所有人。
     *
     * <p>空列表不缓存：刚上线还没帖子的站点如果缓存了空页，
     * 一旦失效逻辑出问题就会一直显示空首页，宁可每次查库。
     */
    public void write(String by, String order, int size,
                      List<PostVO> sharedItems, Long nextCursor, boolean hasMore) {
        if (sharedItems == null || sharedItems.isEmpty()) {
            return;
        }
        try {
            Snapshot snap = new Snapshot();
            snap.setItems(sharedItems);
            snap.setNextCursor(nextCursor);
            snap.setHasMore(hasMore);
            redis.opsForValue().set(buildKey(by, order, size), om.writeValueAsString(snap),
                    java.time.Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception e) {
            log.warn("feed page cache write failed, ignore. cause={}", e.toString());
        }
    }

    /**
     * 让所有已缓存的首页失效。
     *
     * <p>必须在「帖子会出现在/消失在首页」的动作处调用：发帖即通过、审核通过、
     * 删帖、圈内下架、解散圈子的级联下架、恢复圈子。
     * 漏掉任何一处，被删的内容会继续在首页多活最多 60 秒——
     * 这是内容安全问题，不是性能问题。
     *
     * <p><b>为什么延迟到事务提交后</b>：这些动作都在 {@code @Transactional} 里。
     * 如果在方法体中途就失效，事务还没提交，另一个请求正好在这时查库
     * 会把「旧数据」重新写回缓存，然后带着旧数据活 60 秒——比不失效还糟。
     * 提交后再失效，才能保证下一次读到的必然是提交后的新数据。
     * 无活动事务（比如测试直接调用）时立即执行，行为不变。
     */
    public void invalidate() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bumpVersion();
                }
            });
        } else {
            bumpVersion();
        }
    }

    private void bumpVersion() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            // 版本号加不上只意味着「旧缓存最多多活 60 秒」，不影响正确性
            log.warn("feed page cache invalidate failed, will expire by ttl. cause={}", e.toString());
        }
    }

    /**
     * 拼键。by / order 都做白名单归一化：
     * by 来自前端参数，若不归一化，攻击者用随机字符串能造出无限多的键把内存占满。
     */
    private String buildKey(String by, String order, int size) {
        return PAGE_PREFIX + ":v" + currentVersion()
                + ":" + normalizeBy(by)
                + ":" + ("asc".equalsIgnoreCase(order) ? "asc" : "desc")
                + ":" + size;
    }

    private String currentVersion() {
        try {
            String v = redis.opsForValue().get(VERSION_KEY);
            return v == null || v.isBlank() ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    private String normalizeBy(String by) {
        if (by == null) {
            return "time";
        }
        return switch (by.toLowerCase()) {
            case "like", "comment", "view" -> by.toLowerCase();
            default -> "time";
        };
    }
}
