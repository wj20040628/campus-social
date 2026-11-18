package com.campus.social.service.support;

import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 注册提醒的合并、脱敏、提交后发送与 webhook 解析。
 *
 * <p>用本机 HttpServer 接住真实的 HTTP 请求，验证发出去的 JSON 就是群机器人
 * 要的 {@code {"msgtype":"text","text":{"content":"..."}}}，而不是只测内部字符串。
 */
class AdminRegistrationAlertServiceTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.set(new String(body, StandardCharsets.UTF_8));
            byte[] reply = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBatchedMessageWithMaskedEmailAndTotals() throws Exception {
        AdminRegistrationAlertService service = service(hookUrl(), "");
        service.record(user(1001L, "小明", "student123@campus.test", null));
        service.record(user(1002L, "小红", "another@campus.test", "20230001"));

        service.flush();

        assertNotNull(received.get(), "应发出 webhook 请求");
        JsonNode json = om.readTree(received.get());
        assertEquals("text", json.path("msgtype").asText());
        String content = json.path("text").path("content").asText();
        assertTrue(content.contains("【友声】新用户注册"));
        assertTrue(content.contains("新增 2 人，累计 128 人"));
        assertTrue(content.contains("小明（ID 1001）"));
        assertTrue(content.contains("st***3@campus.test"));
        assertTrue(content.contains("未填学号"));
        assertTrue(content.contains("已登记学号"));
        assertFalse(content.contains("student123@campus.test"), "提醒里不能出现完整邮箱");
    }

    @Test
    void waitsForCommitBeforeQueueing() {
        AdminRegistrationAlertService service = service(hookUrl(), "");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.record(user(1003L, "待提交", "pending@campus.test", null));
            service.flush();
            assertNull(received.get(), "事务未提交时不应发送");

            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        service.flush();
        assertNotNull(received.get(), "提交后应发送");
        assertTrue(received.get().contains("待提交"));
    }

    @Test
    void readsWebhookFromEnvFileWhenEnvironmentEmpty() throws Exception {
        Path env = Files.createTempFile("campus-env-", ".env");
        Files.writeString(env, "MYSQL_USER=x\nexport ALERT_WEBHOOK_URL=\"" + hookUrl() + "\"\n",
                StandardCharsets.UTF_8);
        try {
            AdminRegistrationAlertService service = service("", env.toString());
            assertEquals(hookUrl(), service.resolveWebhook());
            service.record(user(1004L, "来自.env", "envfile@campus.test", null));
            service.flush();
            assertTrue(received.get().contains("来自.env"));
        } finally {
            Files.deleteIfExists(env);
        }
    }

    @Test
    void dropsQuietlyWhenNoWebhookConfigured() {
        AdminRegistrationAlertService service = service("", "");
        service.record(user(1005L, "无配置", "none@campus.test", null));
        service.flush();
        assertNull(received.get());
    }

    @Test
    void messageTruncatesLongBatches() {
        AdminRegistrationAlertService service = service("", "");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            lines.add("用户" + i + "（ID " + i + "）｜u***" + i + "@campus.test｜未填学号");
        }
        String message = service.buildMessage(lines, 100L, LocalDateTime.of(2026, 11, 13, 15, 20));

        assertTrue(message.contains("新增 12 人，累计 100 人"));
        assertTrue(message.contains("…另有 4 人"));
        assertTrue(message.contains("8. 用户8"));
        assertFalse(message.contains("9. 用户9"), "超过上限的人不应逐条列出");
        assertEquals(AdminRegistrationAlertService.MAX_NAMES, 8);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private AdminRegistrationAlertService service(String webhook, String envFile) {
        UserMapper mapper = mock(UserMapper.class);
        lenient().when(mapper.selectCount(any())).thenReturn(128L);
        AdminRegistrationAlertService service = new AdminRegistrationAlertService(mapper, om);
        ReflectionTestUtils.setField(service, "configuredWebhook", webhook);
        ReflectionTestUtils.setField(service, "envFile", envFile);
        return service;
    }

    private static User user(long id, String nickname, String email, String studentNo) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setEmail(email);
        u.setStudentNo(studentNo);
        u.setStatus(0);
        return u;
    }
}
