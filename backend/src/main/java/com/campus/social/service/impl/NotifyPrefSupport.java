package com.campus.social.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 通知偏好的解析与合并（2026-10-25，配邮件摘要）。
 *
 * <p>user.notify_pref 是一个 JSON 列，正在长出两个独立段：
 * <pre>
 *   {"disabled":[3,9],            站内通知免打扰(PostNotifySupport 的老口径)
 *    "emailDigest":{"enabled":true,"types":["chat","comment"]}}
 *                                邮件摘要订阅(2026-10-25 新增)
 * </pre>
 *
 * <p><b>为什么所有写入口都必须走这里的 merge 而不是整列覆盖</b>：
 * 两个设置页（通知开关 / 邮件摘要）各写各的段，任何一个用「整体覆盖」
 * 都会把对方刚保存的段抹掉——实测前 PUT /notify-pref 就是覆盖写，
 * 若不改造，用户调一下免打扰就把邮件订阅清空了。合并单点收在这里。
 *
 * <p>老数据兼容：无 emailDigest 段 = 默认关闭（不主动给用户发邮件，
 * 邮件是要 opt-in 的打扰，与站内通知「默认全开」语义相反）。
 */
@Service
@RequiredArgsConstructor
public class NotifyPrefSupport {

    /** 邮件摘要可订阅的类型组(语义 key → notify.type 集合)。与前端 SettingsNotify 同步 */
    public static final java.util.Map<String, java.util.List<Integer>> DIGEST_GROUPS = java.util.Map.of(
            "chat", java.util.List.of(4),                    // 新搭话/私信
            "comment", java.util.List.of(3, 9, 10),          // 评论 / @你 / 集市留言
            "like", java.util.List.of(2),                     // 被赞
            "follow", java.util.List.of(1),                   // 新粉丝
            "system", java.util.List.of(5, 6, 7, 8, 12)       // 活动变更 / 审核结果
    );

    private final ObjectMapper om;

    /** 邮件摘要订阅；pref 为空/坏 JSON/无段 = 关闭 */
    public EmailDigest emailDigest(String prefJson) {
        JsonNode node = readRoot(prefJson);
        JsonNode d = node == null ? null : node.get("emailDigest");
        boolean enabled = d != null && d.path("enabled").asBoolean(false);
        Set<String> types = new LinkedHashSet<>();
        if (d != null && d.has("types")) {
            for (JsonNode t : d.get("types")) {
                if (DIGEST_GROUPS.containsKey(t.asText())) {
                    types.add(t.asText());
                }
            }
        }
        return new EmailDigest(enabled, types);
    }

    /** 合并写入 emailDigest 段,保留 disabled 段与其它未知段 */
    public String mergeEmailDigest(String oldJson, boolean enabled, Set<String> types) {
        ObjectNode root = writableRoot(oldJson);
        ObjectNode digest = om.createObjectNode();
        digest.put("enabled", enabled);
        digest.set("types", om.valueToTree(
                types.stream().filter(DIGEST_GROUPS::containsKey).toList()));
        root.set("emailDigest", digest);
        return write(root);
    }

    /** 合并写入 disabled 段(站内通知免打扰),保留 emailDigest 段——修覆盖写隐患 */
    public String mergeDisabled(String oldJson, java.util.Collection<Integer> disabled) {
        ObjectNode root = writableRoot(oldJson);
        root.set("disabled", om.valueToTree(disabled));
        return write(root);
    }

    /** Jackson 受检异常在此吞掉换回空串:写偏好失败时,空串=全开+邮件关,是安全态 */
    private String write(ObjectNode root) {
        try {
            return om.writeValueAsString(root);
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode readRoot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return null; // 坏 JSON 当作无偏好(与 PostNotifySupport.parseDisabled 同口径)
        }
    }

    private ObjectNode writableRoot(String json) {
        JsonNode node = readRoot(json);
        return node instanceof ObjectNode o ? o : om.createObjectNode();
    }

    /** 邮件摘要订阅档案 */
    public record EmailDigest(boolean enabled, Set<String> types) {
    }
}
