package com.campus.social.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 新用户注册提醒：把注册事件合并后推到运维告警群（企业微信/钉钉机器人）。
 *
 * <p><b>为什么合并发送</b>：群机器人有每分钟 20 条的上限，宣传期集中注册时
 * 一人一条会被限流丢消息；改成每 60 秒合并一条，既不漏也不刷屏。
 *
 * <p><b>为什么复用 ALERT_WEBHOOK_URL</b>：备份与巡检告警已经在用同一个群机器人，
 * 站长不需要再配一套。生产的 systemd 不一定把 /opt/campus/.env 注入 JVM，
 * 所以优先读环境变量，取不到再直接读该文件；两处都没有就只记日志，不影响注册。
 *
 * <p><b>绝不影响注册</b>：入队是内存操作；HTTP 在定时任务里发，失败只记日志。
 * 事务内注册的事件在提交后才入队，注册校验把事务回滚时不会误报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRegistrationAlertService {

    /** 单条消息最多列几个人。再多会接近企业微信 2048 字节的内容上限。 */
    static final int MAX_NAMES = 8;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String ENV_KEY = "ALERT_WEBHOOK_URL=";

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${campus.admin-alert.webhook-url:}")
    private String configuredWebhook;

    @Value("${campus.admin-alert.env-file:/opt/campus/.env}")
    private String envFile;

    private volatile String webhook;
    private volatile boolean webhookResolved;

    /** 启动时解析一次并在日志里点明是否启用，部署后用 grep 就能确认配置是否生效。 */
    @PostConstruct
    void logStartupStatus() {
        if (resolveWebhook() != null) {
            log.info("注册提醒已启用：新用户注册后合并推送到告警群");
        }
    }

    /** 注册成功后调用。事务内会延迟到提交后入队；回滚不会报。 */
    public void record(User user) {
        if (user == null) {
            return;
        }
        String line = format(user);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pending.add(line);
                }
            });
        } else {
            pending.add(line);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void flush() {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = pending.poll()) != null) {
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return;
        }
        String url = resolveWebhook();
        if (url == null) {
            log.warn("新用户提醒未发送（未配置 ALERT_WEBHOOK_URL），本次 {} 人", lines.size());
            return;
        }
        long total = userMapper.selectCount(
                new LambdaQueryWrapper<User>().ne(User::getStatus, 2));
        send(url, buildMessage(lines, total, LocalDateTime.now()));
    }

    /** 解析一次后缓存：部署重启后重新读取。包级可见便于测试。 */
    String resolveWebhook() {
        if (webhookResolved) {
            return webhook;
        }
        synchronized (this) {
            if (webhookResolved) {
                return webhook;
            }
            String url = trimToNull(configuredWebhook);
            if (url == null) {
                url = readEnvFile();
            }
            webhook = url;
            webhookResolved = true;
            if (url == null) {
                log.warn("未配置注册提醒 webhook（ALERT_WEBHOOK_URL 为空），注册事件只写日志");
            }
            return url;
        }
    }

    String buildMessage(List<String> lines, long total, LocalDateTime now) {
        int shown = Math.min(lines.size(), MAX_NAMES);
        StringBuilder sb = new StringBuilder();
        sb.append("【友声】新用户注册 ").append(now.format(TIME)).append('\n');
        sb.append("新增 ").append(lines.size()).append(" 人，累计 ").append(total).append(" 人\n");
        for (int i = 0; i < shown; i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
        if (lines.size() > shown) {
            sb.append("…另有 ").append(lines.size() - shown).append(" 人，详见管理后台");
        }
        return sb.toString().trim();
    }

    private void send(String url, String message) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("msgtype", "text");
            body.putObject("text").put("content", message);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String reply = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 != 2) {
                log.warn("新用户提醒发送失败：HTTP {}", response.statusCode());
            } else if (reply.contains("\"errcode\"") && !reply.contains("\"errcode\":0")) {
                // 群机器人 200 也可能带错误码（webhook 失效、关键词不匹配等）
                log.warn("新用户提醒被机器人拒绝：{}", reply);
            } else {
                log.info("新用户提醒已发送");
            }
        } catch (Exception e) {
            log.warn("新用户提醒发送异常：{}", e.toString());
        }
    }

    private String format(User user) {
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? "未设昵称" : user.getNickname();
        String email = user.getEmail() == null || user.getEmail().isBlank()
                ? "无邮箱" : UserVoAssembler.maskUsername(user.getEmail());
        String campus = user.getStudentNo() == null || user.getStudentNo().isBlank()
                ? "未填学号" : "已登记学号";
        return nickname + "（ID " + user.getId() + "）｜" + email + "｜" + campus;
    }

    private String readEnvFile() {
        if (envFile == null || envFile.isBlank()) {
            return null;
        }
        Path path = Path.of(envFile.trim());
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                if (line.startsWith(ENV_KEY)) {
                    return trimToNull(unquote(line.substring(ENV_KEY.length())));
                }
            }
        } catch (Exception e) {
            log.debug("读取 {} 失败：{}", envFile, e.toString());
        }
        return null;
    }

    private static String unquote(String value) {
        String s = value.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }
}
