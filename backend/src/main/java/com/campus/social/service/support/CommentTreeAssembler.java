package com.campus.social.service.support;

import com.campus.social.entity.Comment;
import com.campus.social.entity.User;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论树组装（把「一批扁平行」变成「一级评论 + children 树」）。
 *
 * <p>从 {@code CommentServiceImpl} 拆出来的理由是职责边界：
 * 那边管「评论怎么写、通知发给谁」（写侧+ 权限），
 * 这边管「评论怎么读、树怎么拼」（读侧 + 纯转换），
 * 两者变更频率与改动原因完全不同。
 *
 * <p><b>本类不发 N+1 查询</b>：作者、被回复人、点赞态、点赞数
 * 全部先用批量接口取回，组树过程只做内存操作。
 * 一页 20 条一级评论 + 各自子孙，查询次数固定为 4 次，与数据量无关。
 */
@Component
@RequiredArgsConstructor
public class CommentTreeAssembler {

    /** 每个节点的直接子回复上限。超限取最老的，溢出条数如实回传 */
    public static final int MAX_CHILDREN_PER_NODE = 20;

    private final UserMapper userMapper;
    private final LikeMapper likeMapper;
    private final ObjectMapper om;

    /**
     * 组装一棵评论树。
     *
     * @param roots   一级评论（已按id 倒序，且已按分页截断）
     * @param replies 该帖全部二三级回复，按 {@code level ASC, id ASC}
     * @param viewerId 当前浏览者，用于判 liked；&lt;= 0 表示未登录
     */
    public List<CommentVO> assemble(List<Comment> roots, List<Comment> replies, long viewerId) {
        if (roots.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = loadUsers(roots, replies);
        Set<Long> liked = loadLiked(viewerId, roots, replies);
        Map<Long, Integer> likeCounts = loadLikeCounts(roots, replies);

        // 先把所有要展示的评论平铺成 VO，一行一次用户查询都没有
        Map<Long, CommentVO> voById = new LinkedHashMap<>();
        for (Comment c : concat(roots, replies)) {
            voById.put(c.getId(), toVo(c, users, liked, likeCounts));
        }

        // 二级与三级都是「一级评论的直接回复」，按 parentId(= 一级 id)归到同一个桶。
        //
        // <p><b>为什么不把三级挂到它真正回复的那条二级下面</b>：
        // openapi 契约规定 level=3 的 parent_id 填「其一级祖先的 id」，
        // 也就是「被回复评论所在那一级回复的根」，
        // 而二级与三级的 parent_id 存的是同一个值（都指向一级）。
        // 换句话说<b>数据库里已经没有「三级回复了哪条二级」这个信息</b>，
        // 用 replyToUserId 反推也不可靠——同一个人在同一条评论下可能有多条二级。
        // 硬猜会把回复挂到错误的分支上，那比不挂更难排查。
        //
        // <p><b>平铺到同一层为什么符合设计</b>：契约另有
        // {@code MAX_RENDER_DEPTH=3} 与 {@code MAX_VISUAL_DEPTH=2} 两个独立约束，
        // 三级封顶是「渲染 3 行」，而视觉缩进只有 2 层——
        // 即三级与二级本就同缩进，「谁回复谁」由 replyToNickname 文字承担，
        // 不靠缩进表达。同层平铺既不丢任何回复，也不伪造错误的层级。
        Map<Long, List<Comment>> repliesByRoot = new HashMap<>();
        for (Comment c : replies) {
            if (c.getLevel() != null && c.getLevel() >= 2) {
                repliesByRoot.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<CommentVO> result = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            CommentVO rootVo = voById.get(root.getId());
            List<CommentVO> l2 = new ArrayList<>();
            List<Comment> childRows = repliesByRoot.get(root.getId());
            if (childRows != null) {
                for (Comment c2 : childRows) {
                    // 每项的 children 都是空列表（三级封顶），前端按 level 决定缩进
                    l2.add(voById.get(c2.getId()));
                }
            }
            // 必须显式写回：上面的 l2 是一个新建的 ArrayList，
            // 与 rootVo.children 是两个不同的对象引用。
            // 只把它当参数传给 applyOverflow 不会让 rootVo 看到内容——
            // 这种「引用当值用」的错编译能过、单测难覆盖，
            // 只有真机调一次接口才会发现 children 恒为空。
            rootVo.setChildren(l2);
            applyOverflow(rootVo);
            result.add(rootVo);
        }
        return result;
    }

    /**
     * 每个节点的直接子回复最多 {@link #MAX_CHILDREN_PER_NODE} 条。
     *
     * <p>取最老的（id 升序在前）：回复链的价值在于连续可读，
     * 丢最新的那条反而让对话断在末尾。
     *
     * <p>溢出条数必须如实回传：只给 20 条而不说「还有 N 条」，
     * 用户会以为回复被系统吞了。
     */
    private void applyOverflow(CommentVO parent) {
        List<CommentVO> children = parent.getChildren();
        if (children.size() > MAX_CHILDREN_PER_NODE) {
            parent.setChildrenOverflowCount(children.size() - MAX_CHILDREN_PER_NODE);
            children.subList(MAX_CHILDREN_PER_NODE, children.size()).clear();
        }
    }

    /**
     * 组装叶子列表（每项children 恒为空）。
     *
     * <p>供溢出补齐端点 {@code GET /post/{id}/comment/{commentId}/children} 用：
     * 那个端点按契约只查直接子回复、不递归，所以不需要建树，
     * 但仍要批量取作者与点赞态，否则一页 20 条就是 40 次查询。
     *
     * <p>{@code children} 置空列表而非 null：前端遍历 children 时不必判空，
     * 这是契约里「三级 children 恒为[]」的直接推论。
     */
    public List<CommentVO> assembleAsLeaves(List<Comment> rows, long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = loadUsers(rows, List.of());
        Set<Long> liked = loadLiked(viewerId, rows, List.of());
        Map<Long, Integer> likeCounts = loadLikeCounts(rows, List.of());
        List<CommentVO> list = new ArrayList<>(rows.size());
        for (Comment c : rows) {
            list.add(toVo(c, users, liked, likeCounts));
        }
        return list;
    }

    // ---------------------------------------------------------------- 批量取数

    /** 作者 + 被回复人，一次 IN 查询取回（同一页最多 2N 个不重复 id） */
    private Map<Long, User> loadUsers(List<Comment> roots, List<Comment> replies) {
        Set<Long> ids = new HashSet<>();
        for (Comment c : concat(roots, replies)) {
            ids.add(c.getUserId());
            if (c.getReplyToUserId() != null) {
                ids.add(c.getReplyToUserId());
            }
        }
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /** 当前浏览者赞过哪些（viewerId <= 0 是未登录，跳过查询） */
    private Set<Long> loadLiked(long viewerId, List<Comment> roots, List<Comment> replies) {
        if (viewerId <= 0) {
            return Set.of();
        }
        Set<Long> ids = idsOf(roots, replies);
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(likeMapper.selectLikedCommentIds(viewerId, ids));
    }

    private Map<Long, Integer> loadLikeCounts(List<Comment> roots, List<Comment> replies) {
        Set<Long> ids = idsOf(roots, replies);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> map = new HashMap<>();
        for (LikeMapper.LikeCountRow row : likeMapper.countByTargetIds(ids)) {
            map.put(row.getTargetId(), row.getCnt());
        }
        return map;
    }

    private Set<Long> idsOf(List<Comment> roots, List<Comment> replies) {
        Set<Long> ids = new HashSet<>();
        for (Comment c : concat(roots, replies)) {
            ids.add(c.getId());
        }
        return ids;
    }

    private List<Comment> concat(List<Comment> roots, List<Comment> replies) {
        if (replies.isEmpty()) {
            return roots;
        }
        List<Comment> all = new ArrayList<>(roots.size() + replies.size());
        all.addAll(roots);
        all.addAll(replies);
        return all;
    }

    // ---------------------------------------------------------------- 行 -> VO

    private CommentVO toVo(Comment c, Map<Long, User> users, Set<Long> liked,
            Map<Long, Integer> likeCounts) {
        User u = users.get(c.getUserId());
        UserVO userVo = UserVoAssembler.of(u);
        User rep = c.getReplyToUserId() == null ? null : users.get(c.getReplyToUserId());
        // 被回复人昵称同样走装配器取脱敏值：直接读 rep.getUsername() 会让
        // 「昵称为空的用户」在「回复 @某人」时被原样暴露，而这恰恰是最容易被忽略的一条路径
        UserVO repVo = UserVoAssembler.of(rep);
        String replyNick = repVo == null ? null : repVo.getNickname();

        return CommentVO.builder()
                .id(c.getId())
                .postId(c.getPostId())
                .userId(c.getUserId())
                .user(userVo)
                .parentId(c.getParentId())
                .level(c.getLevel())
                .replyToUserId(c.getReplyToUserId())
                .replyToNickname(replyNick)
                .content(c.getContent())
                .mentioned(parseMentioned(c.getMentioned()))
                .likeCount(likeCounts.getOrDefault(c.getId(), 0))
                .liked(liked.contains(c.getId()))
                .createTime(c.getCreateTime())
                // 三级恒为空列表，前端遍历 children 时不必判 null
                .children(new ArrayList<>())
                .childrenOverflowCount(0)
                .build();
    }

    /** 解析 comment.mentioned JSON；失败返回 null 而不是抛异常（不阻塞评论展示） */
    private Map<String, Long> parseMentioned(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json, new TypeReference<Map<String, Long>>() { });
        } catch (Exception e) {
            return null;
        }
    }
}