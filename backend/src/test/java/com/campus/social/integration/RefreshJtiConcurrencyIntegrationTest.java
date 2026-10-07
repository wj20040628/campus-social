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

        // 核心:并发下恰好 1 个滚动成功,其余全部 REUSED(0 个"意外成功"、0 个其它异常)
        assertEquals(1, winners, "同 jti 并发 refresh 必须只有 1 个成功");
        assertEquals(THREADS - 1, reused, "其余线程必须全部判定为 token 重用");
        assertEquals(0, others, "不应出现 REUSED/成功 之外的异常");
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
