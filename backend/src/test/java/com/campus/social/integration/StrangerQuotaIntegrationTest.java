package com.campus.social.integration;

import com.campus.social.config.CampusProperties;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.vo.StrangerQuotaVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 陌生人搭话额度回归(2026-10-28 重写)。
 *
 * <p><b>规则</b>:对同一个陌生人每天 {@code perPeerDailyLimit}(默认 1) 条;
 * <b>不再有「每天 N 个新对象」</b> —— 对象数量不是风险,对单个对象的骚扰频率才是。
 * 对方关注我 / 回复过我之后,由 {@code StrangerPolicy} 判成「非陌生人」,
 * 调用方直接跳过本服务(那一段在 ChatGuardIntegrationTest 里验)。
 *
 * <p><b>为什么删掉旧的三条</b>:旧断言写死「3 个新对象 + 同一人 2 条」,
 * 规则一改就成了反向断言 —— 它会在正确实现上失败,把人引去改实现而不是改测试。
 *
 * <p>测试用户 id 只做 Redis 键用,不落库;结束时按 key 前缀清理,
 * 否则同一台机器隔天再跑会读到上一次窗口里的计数。
 */
@SpringBootTest
@ActiveProfiles("local")
class StrangerQuotaIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100_000L);

    @Autowired
    private StrangerQuotaService quotaService;
    @Autowired
    private CampusProperties campus;
    @Autowired
    private StringRedisTemplate redis;

    private final List<Long> usedUsers = new ArrayList<>();

    private long user() {
        long u = 10_000_000L + SEQ.incrementAndGet();
        usedUsers.add(u);
        return u;
    }

    @AfterEach
    void cleanup() {
        for (long u : usedUsers) {
            Set<String> keys = redis.keys("quota:stranger:perpeer:" + u + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    @Test
    void configIsOneMessagePerPeerPerDay() {
        assertEquals(1, campus.getPerPeerDailyLimit(),
                "规则基线:对同一陌生人每天 1 条。改这个值等于改产品规则,请同步测试与前端文案");
    }

    /**
     * 第 1 条通过且返回剩余 0;第 2 条被拒。
     * 返回值是「本次发送后的剩余」,前端拿它刷新会话内规则行,
     * 返回 1 会让那句话一直写着「还剩 1 条」而实际上发不出去了。
     */
    @Test
    void firstMessageConsumesTheOnlySlot() {
        long me = user();
        long peer = me + 1;

        assertEquals(0, quotaService.consumePerPeer(me, peer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotaService.consumePerPeer(me, peer));
        assertTrue(ex.getMessage().contains("明天再来"), ex.getMessage());
        // 文案必须给出解锁出路:只说「超限」用户会以为要等一整天
        assertTrue(ex.getMessage().contains("对方关注你或回复你"), ex.getMessage());
    }

    /**
     * 对象数量不设上限:对 8 个不同的人各发 1 条应该全部通过。
     * 旧规则下第 4 个人会被拒,这条断言就是「旧额度真的删干净了」的证据。
     */
    @Test
    void numberOfDistinctPeersIsNotLimited() {
        long me = user();
        for (int i = 1; i <= 8; i++) {
            long peer = me + i;
            assertDoesNotThrow(() -> quotaService.consumePerPeer(me, peer),
                    "第 " + peer + " 个人不该被对象数量上限拦住");
        }
    }

    /** 额度按人隔离:给 A 发过不影响 B */
    @Test
    void quotaIsScopedPerPeer() {
        long me = user();
        long a = me + 11;
        long b = me + 12;

        quotaService.consumePerPeer(me, a);
        assertThrows(BusinessException.class, () -> quotaService.consumePerPeer(me, a));
        // B 这边仍是满额
        assertEquals(0, quotaService.consumePerPeer(me, b));
    }

    /** 快照不传 peerId 时,「剩余条数」与「是否陌生人」都必须是 null,不能默认 0 */
    @Test
    void snapshotWithoutPeerOmitsPerPeerFields() {
        long me = user();

        StrangerQuotaVO snap = quotaService.snapshot(me, null);
        assertEquals(1, snap.getPerPeerDailyLimit());
        assertNull(snap.getRemainingPerPeer(), "没有对象就没有「剩余」,0 会被前端读成「发不出去了」");
        assertNull(snap.getIsStranger());
        assertNotNull(snap.getResetAt(), "前端要据此渲染「明天 0 点重置」");
    }

    /** 快照是只读的:反复查不该扣额度 */
    @Test
    void snapshotDoesNotConsumeQuota() {
        long me = user();
        long peer = me + 21;

        quotaService.snapshot(me, peer);
        quotaService.snapshot(me, peer);

        // 查过两次之后仍能发第 1 条
        assertEquals(0, quotaService.consumePerPeer(me, peer));
    }

    /** 发过一条后,快照里的剩余条数归零 */
    @Test
    void snapshotReflectsConsumedSlot() {
        long me = user();
        long peer = me + 31;

        quotaService.consumePerPeer(me, peer);

        StrangerQuotaVO snap = quotaService.snapshot(me, peer);
        assertEquals(0, snap.getRemainingPerPeer().intValue());
        // 这两个测试用户之间没有任何关系行,必然是陌生人
        assertEquals(Boolean.TRUE, snap.getIsStranger());
    }
}
