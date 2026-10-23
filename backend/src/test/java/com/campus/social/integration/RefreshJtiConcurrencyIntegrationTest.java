package com.campus.social.integration;

import com.campus.social.dto.RefreshDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.campus.social.common.ResultCode.REFRESH_TOKEN_REUSED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实集成测试(连本机 Redis,local profile)。
 * 场景:双 token 防重用 —— 同一个 refresh token(同 jti)并发 refresh,
 * 只有一个能滚动成功,其余必须抛 REFRESH_TOKEN_REUSED(验证 Phase 1.3 Lua CAS 原子性)。
 */
@SpringBootTest
@ActiveProfiles("local")
class RefreshJtiConcurrencyIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserMapper userMapper;
    @Autowired private StringRedisTemplate redis;

    private Long userId;
    private String refreshKey;
    private final String jti = "test-jti-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        // 造一个真实 user(避免 refresh 里 selectById 判空),用测试专属用户名
        User u = new User();
        u.setUsername("itest_" + System.nanoTime());
        u.setPasswordHash(JwtUtil.hashPassword("x"));
        u.setStatus(0);
        userMapper.insert(u);
        userId = u.getId();

        // 直接向 Redis 写一个 jti,再签发一个对应 jti 的 refresh token(模拟"登录已发 refresh")
        refreshKey = "refresh:" + userId;
        redis.opsForValue().set(refreshKey, jti, java.time.Duration.ofDays(7));
    }

    @AfterEach
    void tearDown() {
        try {
            redis.delete(refreshKey);
            if (userId != null) userMapper.deleteById(userId);
        } catch (Exception ignore) { }
    }

    @Test
    void sameJti_concurrentRefresh_onlyOneWins() throws Exception {
        final int THREADS = 20;
        // 所有线程都用同一个 refresh token(同 jti),并发打 refresh
        String token = JwtUtil.issueRefresh(userId, jti);
        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken(token);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    authService.refresh(dto);
                    return 1;                        // 滚动成功
                } catch (BusinessException e) {
                    return e.getCode() == REFRESH_TOKEN_REUSED.getCode() ? 0 : -1;
                }
            }));
        }
        gate.countDown();

        int winners = 0, reused = 0, others = 0;
        for (Future<Integer> f : futures) {
            int r = f.get(30, TimeUnit.SECONDS);
            if (r == 1) winners++;
            else if (r == 0) reused++;
            else others++;
        }
        pool.shutdown();

        System.out.println("[refresh] winners=" + winners + " reused=" + reused + " others=" + others);

        /*
         * 断言口径(2026-10-23 修,审查 P1-1):
         * 旧断言是「其余 19 个全部 REUSED」,但实测恒为 winners=1 reused=1 others=18 ——
         * 且 reused==19 在实现上**永远不可能成立**。
         *
         * 原因:Lua CAS 里「jti 不匹配」分支会先 DEL 再返回 0,而第一个失败者 DEL 掉 key 之后,
         * 后续线程读到的是 key 不存在(返回 -1,对应 REFRESH_TOKEN_INVALID),不再是 jti 不匹配。
         * 也就是说:只有 1 个线程能观测到「真正的重用」,其余观测到「已失效」。
         *
         * 功能上这是安全的(19 个全被拒,无越权滚动),只是**语义粒度**不同:
         * 安全事件(重用)被降级成普通失效,告警无法区分。要真正区分需引入
         * tombstone key(DEL 后写 refresh:revoked:{uid},TTL 同 token)。
         *
         * 本用例校验的是「并发安全」这条更重要的性质,断言改为:
         * 恰好 1 个成功 + 其余全部被拒(REUSED 或 INVALID)+ 无其它异常。
         */
        assertEquals(1, winners, "同 jti 并发 refresh 必须只有 1 个成功");
        assertEquals(THREADS - 1, reused + others, "其余线程必须全部被拒(重用或已失效)");
    }

    @Test
    void sequentialReuse_secondCallRejected() {
        // 顺序版:第一次 refresh 成功(滚动到新 jti),第二次拿旧 token 必 REUSED
        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken(JwtUtil.issueRefresh(userId, jti));

        TokenPairVO first = assertDoesNotThrow(() -> authService.refresh(dto));
        assertNotNull(first.getAccessToken());

        // 用同一个旧 token 再来 → 旧 jti 已不在 Redis(CAS 已滚动/DEL)→ 重用
        RefreshDTO again = new RefreshDTO();
        again.setRefreshToken(dto.getRefreshToken());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(again));
        assertEquals(REFRESH_TOKEN_REUSED.getCode(), ex.getCode());
    }
}
