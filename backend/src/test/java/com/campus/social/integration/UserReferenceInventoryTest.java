package com.campus.social.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户引用清单（2026-11-01，1.13.11）。
 *
 * <p>账号注销漏清理的根因不是某一条 SQL 写错，而是新增了引用 user 的表/列
 * 却没有登记注销策略。本测试直接扫描 information_schema：只要出现未登记的
 * 用户引用表，测试失败，强制作者先回答「注销时清、移交、下线还是保留」。
 *
 * <p>白名单只做登记，不替代 {@code AccountCancelCascadeTest} 的行为断言。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class UserReferenceInventoryTest {

    private static final Set<String> USER_REFERENCE_COLUMNS = Set.of(
            "user_id", "creator_id", "owner_id", "sender_id", "author_id",
            "from_user_id", "to_user_id", "actor_id", "receiver_id",
            "handler_id", "reviewer_id", "reply_to_user_id", "operator_id", "admin_id");

    /**
     * 表名 → 注销策略。
     * PURGE=删除该用户的成员/报名/关注关系；REASSIGN=移交或解散；
     * DISABLE=下线不删除；RETAIN=按内容/审计留痕保留。
     */
    private static final Map<String, String> POLICY = policy();

    private static Map<String, String> policy() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("activity", "REASSIGN/CANCEL_OWNED");
        m.put("activity_signup", "PURGE");
        m.put("announcement_read", "RETAIN");
        m.put("audit_log", "RETAIN");
        m.put("chat_conv_pref", "RETAIN");
        m.put("chat_message", "RETAIN");
        m.put("circle", "REASSIGN");
        m.put("circle_member", "PURGE");
        m.put("comment", "RETAIN");
        m.put("feed_item", "RETAIN");
        m.put("feedback", "RETAIN");
        m.put("file_asset", "RETAIN");
        m.put("file_favorite", "RETAIN");
        m.put("group", "REASSIGN");
        m.put("group_member", "PURGE");
        m.put("group_message", "RETAIN");
        m.put("group_read", "PURGE");
        m.put("like", "RETAIN");
        m.put("mkt_activity", "DISABLE");
        m.put("mkt_comment", "RETAIN");
        m.put("notify", "RETAIN");
        m.put("post", "RETAIN");
        m.put("post_favorite", "RETAIN");
        m.put("relation", "PURGE_FOLLOW");
        m.put("report", "RETAIN");
        m.put("review_task", "RETAIN");
        m.put("upload_file", "RETAIN");
        m.put("user_tag", "PURGE");
        return Map.copyOf(m);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyUserReferenceTableIsRegistered() {
        List<String> columns = USER_REFERENCE_COLUMNS.stream().sorted().toList();
        String placeholders = String.join(",", columns.stream().map(c -> "?").toList());
        List<String> unknown = jdbc.queryForList(
                        "SELECT CONCAT(table_name, '.', column_name) AS ref "
                                + "FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() "
                                + "AND column_name IN (" + placeholders + ") "
                                + "ORDER BY table_name, column_name",
                        String.class, columns.toArray()).stream()
                .filter(ref -> !POLICY.containsKey(ref.substring(0, ref.indexOf('.'))))
                .toList();
        assertTrue(unknown.isEmpty(),
                "新增用户引用列未登记注销策略，请先更新 UserReferenceInventoryTest 白名单: " + unknown);
    }

    @Test
    void policyHasNoStaleTable() {
        Set<String> actual = Set.copyOf(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class));
        List<String> stale = POLICY.keySet().stream().filter(t -> !actual.contains(t)).sorted().toList();
        assertTrue(stale.isEmpty(), "白名单中的表已不存在，请清理 UserReferenceInventoryTest: " + stale);
    }

    @Test
    void referenceColumnListIsUniqueAndNonEmpty() {
        assertTrue(USER_REFERENCE_COLUMNS.size() >= 10, "用户引用列清单异常");
        assertTrue(USER_REFERENCE_COLUMNS.contains("user_id"));
        assertTrue(USER_REFERENCE_COLUMNS.contains("owner_id"));
    }
}
