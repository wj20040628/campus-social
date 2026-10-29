package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.entity.ChatMessage;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Comment;
import com.campus.social.entity.FileAsset;
import com.campus.social.entity.GroupMessage;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.Post;
import com.campus.social.entity.User;
import com.campus.social.mapper.ChatMessageMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CommentMapper;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.GroupMessageMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 举报目标快照（2026-10-29）。
 *
 * <p>背景：举报表原来只存 target_id，管理端打开举报时现场查原内容。
 * 作者在管理员处置前删帖（物理删除）、删评论/撤回消息（软删）后，
 * 管理员只剩「内容已删除或不存在」，无法核实举报是否成立。
 *
 * <p>本服务在举报提交时抓一份目标快照，随举报行落库；
 * 管理端优先显示实时内容，实时内容不存在时回退到快照。
 * 快照只给管理端看，不进入任何用户侧接口。
 *
 * <p>失败一律返回 null，不影响举报主流程；历史举报没有快照，
 * 仍按原逻辑显示「已删除」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSnapshotService {

    /** 正文最多留存 4000 字：足够覆盖帖子/评论，避免 TEXT 被长文撑爆 */
    private static final int MAX_CONTENT = 4000;
    /** 媒体 JSON 最多留存 2000 字 */
    private static final int MAX_MEDIA = 2000;
    /** 标题最多留存 200 字 */
    private static final int MAX_TITLE = 200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper om;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final MktActivityMapper mktActivityMapper;
    private final FileAssetMapper fileAssetMapper;
    private final CircleMapper circleMapper;
    private final UserMapper userMapper;

    /** 抓取快照；任何异常都吞掉，只记日志。 */
    public String captureQuietly(int targetType, Long targetId) {
        try {
            return capture(targetType, targetId);
        } catch (Exception e) {
            log.warn("report snapshot failed: type={}, target={}, err={}",
                    targetType, targetId, e.getMessage());
            return null;
        }
    }

    /**
     * 抓取目标快照，返回 JSON；目标已不存在或类型不支持时返回 null。
     * 包内可见，方便单测直调。
     */
    public String capture(int targetType, Long targetId) {
        if (targetId == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("targetType", targetType);
        snap.put("targetId", targetId);
        snap.put("capturedAt", LocalDateTime.now().format(TIME_FMT));

        boolean found = switch (targetType) {
            case ReportTargetType.POST -> fillPost(snap, targetId);
            case ReportTargetType.COMMENT -> fillComment(snap, targetId);
            case ReportTargetType.CHAT_MESSAGE -> fillChatMessage(snap, targetId);
            case ReportTargetType.GROUP_MESSAGE -> fillGroupMessage(snap, targetId);
            case ReportTargetType.USER -> fillUser(snap, targetId);
            case ReportTargetType.MKT_ACTIVITY -> fillMktActivity(snap, targetId);
            case ReportTargetType.FILE -> fillFile(snap, targetId);
            case ReportTargetType.CIRCLE -> fillCircle(snap, targetId);
            default -> false; // 5 搭子未上线，没有可抓的实体
        };
        if (!found) {
            return null;
        }
        try {
            return om.writeValueAsString(snap);
        } catch (Exception e) {
            log.warn("report snapshot serialize failed: type={}, target={}, err={}",
                    targetType, targetId, e.getMessage());
            return null;
        }
    }

    /** 管理端展示用：把快照 JSON 渲染成可读文本；坏数据返回 null。 */
    public String render(String json) {
        JsonNode root = read(json);
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int type = root.path("targetType").asInt(0);
        long targetId = root.path("targetId").asLong(0);
        sb.append("目标：").append(typeLabel(type)).append(" #").append(targetId).append('\n');

        long authorId = root.path("authorId").asLong(0);
        String authorName = text(root, "authorName");
        if (authorId > 0) {
            sb.append("作者：").append(authorName == null ? "已注销" : authorName)
                    .append("（ID ").append(authorId).append("）\n");
        }
        String capturedAt = text(root, "capturedAt");
        if (capturedAt != null) {
            sb.append("举报时间：").append(capturedAt).append('\n');
        }
        String title = text(root, "title");
        if (title != null) {
            sb.append("标题：").append(title).append('\n');
        }
        String content = text(root, "content");
        if (content != null) {
            sb.append("正文：\n").append(content).append('\n');
        }
        String media = text(root, "media");
        if (media != null) {
            sb.append("媒体：\n").append(media).append('\n');
        }
        JsonNode extra = root.path("extra");
        if (extra.isObject() && !extra.isEmpty()) {
            sb.append("其他：");
            var it = extra.fields();
            boolean first = true;
            while (it.hasNext()) {
                var e = it.next();
                if (!first) {
                    sb.append("；");
                }
                sb.append(e.getKey()).append('=').append(e.getValue().asText(""));
                first = false;
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 列表摘要兜底：优先标题，其次正文；用于原内容已删除时的 targetSummary。 */
    public String summary(String json) {
        JsonNode root = read(json);
        if (root == null) {
            return null;
        }
        String text = text(root, "title");
        if (text == null) {
            text = text(root, "content");
        }
        if (text == null) {
            return null;
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /** 快照里的作者 id；用于原内容删除后仍能显示作者。 */
    public Long authorId(String json) {
        JsonNode root = read(json);
        if (root == null) {
            return null;
        }
        long id = root.path("authorId").asLong(0);
        return id > 0 ? id : null;
    }

    public String authorName(String json) {
        JsonNode root = read(json);
        return root == null ? null : text(root, "authorName");
    }

    private boolean fillPost(Map<String, Object> snap, long id) {
        Post p = postMapper.selectById(id);
        if (p == null) {
            return false;
        }
        putAuthor(snap, p.getAuthorId());
        snap.put("content", clip(p.getContent(), MAX_CONTENT));
        putIfPresent(snap, "media", clip(p.getMedia(), MAX_MEDIA));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "status", p.getStatus());
        putIfPresent(extra, "circleId", p.getCircleId());
        putIfPresent(extra, "anonymous", p.getIsAnonymous());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillComment(Map<String, Object> snap, long id) {
        Comment c = commentMapper.selectById(id);
        if (c == null) {
            return false;
        }
        putAuthor(snap, c.getUserId());
        snap.put("content", clip(c.getContent(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "postId", c.getPostId());
        putIfPresent(extra, "parentId", c.getParentId());
        putIfPresent(extra, "level", c.getLevel());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillChatMessage(Map<String, Object> snap, long id) {
        ChatMessage m = chatMessageMapper.selectById(id);
        if (m == null) {
            return false;
        }
        putAuthor(snap, m.getSenderId());
        snap.put("content", clip(m.getContent(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "conversationId", m.getConvId());
        putIfPresent(extra, "receiverId", m.getReceiverId());
        putIfPresent(extra, "recalled", m.getIsRecalled());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillGroupMessage(Map<String, Object> snap, long id) {
        GroupMessage m = groupMessageMapper.selectById(id);
        if (m == null) {
            return false;
        }
        putAuthor(snap, m.getSenderId());
        snap.put("content", clip(m.getContent(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "groupId", m.getGroupId());
        putIfPresent(extra, "recalled", m.getIsRecalled());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillUser(Map<String, Object> snap, long id) {
        User u = userMapper.selectById(id);
        if (u == null) {
            return false;
        }
        putAuthor(snap, u.getId());
        putIfPresent(snap, "title", clip(u.getNickname(), MAX_TITLE));
        putIfPresent(snap, "content", clip(u.getBio(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "verifyStatus", u.getVerifyStatus());
        putIfPresent(extra, "status", u.getStatus());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillMktActivity(Map<String, Object> snap, long id) {
        MktActivity a = mktActivityMapper.selectById(id);
        if (a == null) {
            return false;
        }
        putAuthor(snap, a.getUserId());
        putIfPresent(snap, "title", clip(a.getTitle(), MAX_TITLE));
        putIfPresent(snap, "content", clip(a.getContent(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "category", a.getCategory());
        putIfPresent(extra, "location", a.getLocation());
        putIfPresent(extra, "price", a.getPrice());
        putIfPresent(extra, "expireTime", a.getExpireTime());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillFile(Map<String, Object> snap, long id) {
        FileAsset f = fileAssetMapper.selectById(id);
        if (f == null) {
            return false;
        }
        putAuthor(snap, f.getUserId());
        putIfPresent(snap, "title", clip(f.getTitle(), MAX_TITLE));
        putIfPresent(snap, "content", clip(f.getDescription(), MAX_CONTENT));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "fileName", f.getFileName());
        putIfPresent(extra, "ext", f.getExt());
        putIfPresent(extra, "sizeBytes", f.getSizeBytes());
        putIfPresent(extra, "status", f.getStatus());
        putExtra(snap, extra);
        return true;
    }

    private boolean fillCircle(Map<String, Object> snap, long id) {
        Circle c = circleMapper.selectById(id);
        if (c == null) {
            return false;
        }
        putAuthor(snap, c.getOwnerId());
        putIfPresent(snap, "title", clip(c.getName(), MAX_TITLE));
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "memberCount", c.getMemberCount());
        putIfPresent(extra, "status", c.getStatus());
        putExtra(snap, extra);
        return true;
    }

    private void putAuthor(Map<String, Object> snap, Long authorId) {
        if (authorId == null) {
            return;
        }
        snap.put("authorId", authorId);
        User u = userMapper.selectById(authorId);
        putIfPresent(snap, "authorName", u == null ? null : clip(u.getNickname(), MAX_TITLE));
    }

    private void putExtra(Map<String, Object> snap, Map<String, Object> extra) {
        if (!extra.isEmpty()) {
            snap.put("extra", extra);
        }
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !"".equals(value)) {
            map.put(key, value);
        }
    }

    private String clip(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断）";
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readTree(json);
        } catch (Exception e) {
            log.warn("report snapshot parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String text(JsonNode root, String key) {
        JsonNode n = root.path(key);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.asText("");
        return s.isBlank() ? null : s;
    }

    private String typeLabel(int type) {
        return switch (type) {
            case ReportTargetType.USER -> "用户";
            case ReportTargetType.POST -> "帖子";
            case ReportTargetType.COMMENT -> "评论";
            case ReportTargetType.CHAT_MESSAGE -> "私信消息";
            case ReportTargetType.GROUP_MESSAGE -> "群消息";
            case ReportTargetType.MKT_ACTIVITY -> "集市活动";
            case ReportTargetType.FILE -> "文件";
            case ReportTargetType.CIRCLE -> "圈子";
            default -> "内容";
        };
    }
}
