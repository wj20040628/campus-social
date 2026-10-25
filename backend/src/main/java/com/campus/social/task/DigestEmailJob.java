package com.campus.social.task;

import com.campus.social.entity.User;
import com.campus.social.mapper.NotifyMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.MessageGateway;
import com.campus.social.service.impl.NotifyPrefSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日互动摘要邮件（2026-10-25）。
 *
 * <p><b>回访是冷启动期的生死线</b>：通知全在站内，用户不打开网站就什么都
 * 不知道。此任务每天 18:00 给「开了邮件摘要且昨天有新动态」的用户发一封
 * 汇总——不是即时逐条推（那会变成邮件轰炸），是每天最多一封的轻触达。
 *
 * <p><b>发信通道</b>：复用 MessageGateway.sendNotice——prod 走 SMTP
 * (spring.mail.*,当前 QQ 邮箱授权码),dev 走控制台打印不真发。
 * 量级担忧(QQ 个人邮箱发信限额/封号):冷启动期几百用户、开订阅的是子集、
 * 「无动静不发」再砍一刀,单日几十封在个人 SMTP 承受范围内;真到量级,
 * 阿里云邮件推送(DirectMail)同样走 SMTP 协议,换 host/username/password
 * 三个环境变量即切换,零代码改动。
 *
 * <p><b>节流</b>：逐封间隔 300ms(SMTP 服务商普遍按连接/秒限速);
 * 单条失败只记日志不中断;campus.mail.digest-enabled=false 可整体停发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestEmailJob {

    private final UserMapper userMapper;
    private final NotifyMapper notifyMapper;
    private final MessageGateway messageGateway;
    private final NotifyPrefSupport prefSupport;

    /** 邮件里「查看详情」链接的站点地址 */
    @Value("${campus.mail.site-base:http://127.0.0.1:5173}")
    private String siteBase;

    /** 总开关:邮件通道出问题时(封号/限流)拨 false 停发,不影响站内通知 */
    @Value("${campus.mail.digest-enabled:true}")
    private boolean enabled;

    /** 组 key → 邮件文案 */
    private static final Map<String, String> GROUP_LABELS = Map.of(
            "chat", "条新搭话 / 私信",
            "comment", "条评论、回复或@你",
            "like", "个赞",
            "follow", "位新粉丝",
            "system", "条活动或审核动态"
    );

    /**
     * 每天 18:00(东八区)发送,统计窗口为近 24 小时。
     * 选 18 点:晚饭前后是校园活跃回升点,收到邮件点开正好赶上晚间互动。
     */
    @Scheduled(cron = "0 0 18 * * ?")
    public void sendDailyDigest() {
        if (!enabled) {
            return;
        }
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        // LIKE 粗筛(带 emailDigest 字样的偏好行),Java 精解析——
        // 校园量级几百用户,全量 LIKE 扫一次是毫秒级,不值得为它加列
        List<User> candidates = userMapper.selectDigestCandidates();
        int sent = 0;
        int skippedQuiet = 0;
        for (User u : candidates) {
            try {
                if (sendOne(u, since)) {
                    sent++;
                    Thread.sleep(300); // SMTP 节流
                } else {
                    skippedQuiet++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("digest mail failed (non-fatal): user={}, {}", u.getId(), e.getMessage());
            }
        }
        log.info("daily digest done: sent={}, quietSkipped={}, candidates={}",
                sent, skippedQuiet, candidates.size());
    }

    /** @return true=已发送;false=无动静/未开订阅/无邮箱(静默跳过) */
    private boolean sendOne(User u, LocalDateTime since) {
        NotifyPrefSupport.EmailDigest d = prefSupport.emailDigest(u.getNotifyPref());
        if (!d.enabled() || d.types().isEmpty()) {
            return false;
        }
        if (u.getEmail() == null || !u.getEmail().contains("@")) {
            return false; // 没绑邮箱的订阅用户:跳过(设置页有绑定入口)
        }

        Map<Integer, Long> counts = new HashMap<>();
        for (Map<String, Object> row : notifyMapper.countByTypeSince(u.getId(), since)) {
            Object type = row.get("type");
            Object cnt = row.get("cnt");
            if (type instanceof Number t && cnt instanceof Number c) {
                counts.put(t.intValue(), c.longValue());
            }
        }

        StringBuilder lines = new StringBuilder();
        long total = 0;
        for (String group : d.types()) {
            long n = NotifyPrefSupport.DIGEST_GROUPS.getOrDefault(group, List.of()).stream()
                    .mapToLong(t -> counts.getOrDefault(t, 0L)).sum();
            if (n > 0) {
                lines.append("<li>").append(n).append(' ')
                        .append(GROUP_LABELS.getOrDefault(group, "条新动态")).append("</li>");
                total += n;
            }
        }
        if (total == 0) {
            return false; // 无动静不发:零内容的邮件是纯打扰
        }

        String html = "<div style=\"font-family:-apple-system,PingFang SC,sans-serif;max-width:560px;margin:0 auto\">"
                + "<h2 style=\"color:#241C15\">你在友声的今日动态</h2>"
                + "<p style=\"color:#4A4033\">过去 24 小时:</p><ul>" + lines + "</ul>"
                + "<p style=\"margin-top:24px\"><a href=\"" + siteBase + "/notify\" "
                + "style=\"background:#0E7490;color:#fff;padding:10px 22px;border-radius:999px;"
                + "text-decoration:none;display:inline-block\">去友声看看</a></p>"
                + "<p style=\"color:#75695A;font-size:12px;margin-top:24px\">"
                + "每天最多一封。回复不了这封信;想调整或关闭,到 设置 → 通知 → 邮件摘要。</p></div>";
        messageGateway.sendNotice(u.getEmail(), "你在友声的今日动态", html);
        return true;
    }
}
