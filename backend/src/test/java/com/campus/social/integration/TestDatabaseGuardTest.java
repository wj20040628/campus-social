package com.campus.social.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 防回归守卫：整套集成测试必须连独立测试库/Redis 库，绝不能落到开发库。
 * 若 application-test.yml 被删、profile 顺序被改、或有人去掉 test profile，
 * 本测试会先失败，避免测试悄悄污染 campus_social_v2。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class TestDatabaseGuardTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void integrationTestsUseIsolatedDatabases() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("campus_social_test", connection.getCatalog());
        }
        assertInstanceOf(LettuceConnectionFactory.class, redis.getConnectionFactory());
        assertEquals(15, ((LettuceConnectionFactory) redis.getConnectionFactory()).getDatabase());
    }
}
