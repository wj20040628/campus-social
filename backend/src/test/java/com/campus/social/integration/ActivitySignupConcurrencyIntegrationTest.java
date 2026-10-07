package com.campus.social.integration;

import com.campus.social.entity.Activity;
import com.campus.social.entity.ActivitySignup;
import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ActivitySignupMapper;
import com.campus.social.service.ActivityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实集成测试(连本机 MySQL + Redis,local profile)。
 * 场景:防超卖 —— capacity 有限时,并发 N 人报名,current_count 最终必 == capacity,无一人超卖。
 *
 * 前置:本机 MySQL(campus_social_v2)与 Redis(6379)须已启动。
 * 清理:每个测试前删掉自己造的数据,避免互相污染。
 */
@SpringBootTest
@ActiveProfiles("local")
class ActivitySignupConcurrencyIntegrationTest {

    @Autowired private ActivityService activityService;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ActivitySignupMapper signupMapper;

    private final long circleless = -1L; // 无圈子活动

    @AfterEach
    void cleanup(long activityId) {
        // 尽力清理,失败不影响断言
        try {
            signupMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivitySignup>()
                    .eq(ActivitySignup::getActivityId, activityId));
            activityMapper.deleteById(activityId);
        } catch (Exception ignore) { }
    }

    private long createActivity(int capacity) {
        Activity a = new Activity();
        a.setTitle("concurrency-test-" + System.nanoTime());
        a.setCapacity(capacity);
        a.setCurrentCount(0);
        a.setStatus(0);
        a.setStartTime(LocalDateTime.now().minusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setCircleId(null);
        activityMapper.insert(a);
        return a.getId();
    }

    @Test
    void concurrentSignin_neverOversells() throws Exception {
        final int CAPACITY = 5;
        final int CONCURRENT = 30;                 // 远超名额
        final long ACTIVITY = createActivity(CAPACITY);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
            List<Future<Boolean>> futures = new ArrayList<>();
            CountDownLatch startGate = new CountDownLatch(1);   // 所有线程齐步冲,拉满并发
            CountDownLatch finished = new CountDownLatch(CONCURRENT);

            for (int i = 0; i < CONCURRENT; i++) {
                final long userId = 1_000_000L + i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        activityService.signup(ACTIVITY, userId);
                        return true;
                    } catch (Exception e) {
                        return false;               // 满员/被拒 → false,不算错误
                    } finally {
                        finished.countDown();
                    }
                }));
            }
            startGate.countDown();                  // 开闸:30 人同时报名
            finished.await(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 关键断言:最终 current_count 必须恰好 == capacity,且 signup 行数 == capacity
            Activity after = activityMapper.selectById(ACTIVITY);
            long signupRows = signupMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivitySignup>()
                            .eq(ActivitySignup::getActivityId, ACTIVITY));

            assertEquals(CAPACITY, after.getCurrentCount(), "current_count 不得超 capacity(防超卖)");
            assertEquals(CAPACITY, signupRows, "报名行数必须恰等于 capacity,不多不少");
            assertEquals(1, after.getStatus().intValue(), "满员后 status 应翻为 1(已满)");
            assertTrue(after.getCurrentCount() <= CAPACITY, "绝不允许 current_count > capacity");
        } finally {
            cleanup(ACTIVITY);
        }
    }

    @Test
    void concurrentSignin_unlimitedCapacity_neverFails() throws Exception {
        final int CONCURRENT = 20;
        final long ACTIVITY = createActivity(0);    // 0 = 不限额
        try {
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT; i++) {
                final long userId = 2_000_000L + i;
                futures.add(pool.submit(() -> {
                    activityService.signup(ACTIVITY, userId);
                    return true;
                }));
            }
            for (Future<Boolean> f : futures) {
                // 不限额:每个都应成功(get 会抛异常的话断言失败)
                assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            Activity after = activityMapper.selectById(ACTIVITY);
            long signupRows = signupMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivitySignup>()
                            .eq(ActivitySignup::getActivityId, ACTIVITY));
            assertEquals(CONCURRENT, after.getCurrentCount(), "不限额应累计到并发数");
            assertEquals(CONCURRENT, signupRows);
            assertEquals(0, after.getStatus().intValue(), "不限额永不翻 1");
        } finally {
            cleanup(ACTIVITY);
        }
    }
}
