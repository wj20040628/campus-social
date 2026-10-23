package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.entity.Circle;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.Post;
import com.campus.social.entity.User;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局搜索:帖子(全文检索 + LIKE 兜底)+ 用户(昵称 LIKE)+ 圈子(名 LIKE)
 * 帖子走 MySQL FULLTEXT(ngram 中文分词),校园量级够用;后续量大再上 ES
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final MktActivityMapper mktActivityMapper;

    public SearchController(PostMapper postMapper, UserMapper userMapper, CircleMapper circleMapper,
                            CircleMemberMapper circleMemberMapper, MktActivityMapper mktActivityMapper) {
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.circleMapper = circleMapper;
        this.circleMemberMapper = circleMemberMapper;
        this.mktActivityMapper = mktActivityMapper;
    }

    @GetMapping
    public Result<Map<String, Object>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "type", defaultValue = "all") String type,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {

        if (q == null || q.trim().isEmpty()) {
            throw new com.campus.social.exception.BusinessException(
                    com.campus.social.common.ResultCode.BAD_REQUEST, "搜索词不能为空");
        }
        size = PageSize.clamp(size);
        // 当前用户:圈子结果要带「我加没加过」,搜索框在登录后才出现,这里容错取 0
        Long me = (Long) req.getAttribute(com.campus.social.interceptor.AuthInterceptor.ATTR_USER_ID);
        long viewer = me != null ? me : 0L;
        // 帖子:FULLTEXT(ngram 中文分词)+ LIKE 兜底(短词/特殊字符场景)
        // 用户/圈子:数据量小,维持 LIKE
        String keyword = "%" + q.trim() + "%";
        // MATCH/AGAINST 的 keyword 去掉全文语法字(双引号等),防 MATCH 子句解析异常
        String ftKeyword = q.trim().replace("\"", "");
        Map<String, Object> data = new HashMap<>();

        if (type.equals("post") || type.equals("all")) {
            // 1. 全文检索
            List<Post> ftPosts = postMapper.searchByFullText(ftKeyword, size);
            // 2. LIKE 兜底(短词命中、全文漏召回的场景)
            List<Post> likePosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, 1)
                    .like(Post::getContent, keyword)
                    .orderByDesc(Post::getId)
                    .last("LIMIT " + size));
            // 去重(同帖可能两者都命中),按 id 倒序稳定
            java.util.LinkedHashMap<Long, Post> dedup = new java.util.LinkedHashMap<>();
            for (Post p : likePosts) dedup.put(p.getId(), p);
            for (Post p : ftPosts) dedup.putIfAbsent(p.getId(), p);
            data.put("posts", dedup.values().stream().limit(size)
                    .map(this::toPostVo).toList());
        }

        if (type.equals("user") || type.equals("all")) {
            List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .eq(User::getStatus, 0)
                    .like(User::getNickname, keyword)
                    .orderByDesc(User::getId)
                    .last("LIMIT " + size));
            data.put("users", users.stream().map(this::toUserVo).toList());
        }
        // 圈子:数据量小,LIKE
        if (type.equals("circle") || type.equals("all")) {
            List<Circle> circles = circleMapper.selectList(new LambdaQueryWrapper<Circle>()
                    .eq(Circle::getStatus, 0)
                    .like(Circle::getName, keyword)
                    .orderByDesc(Circle::getId)
                    .last("LIMIT " + size));
            data.put("circles", toCircleVos(circles, viewer));
        }
        // 集市(校园限时活动):数据量小,LIKE。仅「已投放(is_enable=1)且未软删」的
        // 活 listings 才进搜索 —— 与帖子 status=1、圈子 status=0 的「可见」口径一致。
        // 满员/过期的不在此过滤(用户搜历史活动也有意义),但软删与下架必过滤。
        if (type.equals("mkt") || type.equals("all")) {
            List<MktActivity> mkts = mktActivityMapper.selectList(new LambdaQueryWrapper<MktActivity>()
                    .eq(MktActivity::getIsDeleted, 0)
                    .eq(MktActivity::getIsEnable, 1)
                    .like(MktActivity::getTitle, keyword)
                    .orderByDesc(MktActivity::getId)
                    .last("LIMIT " + size));
            data.put("mkts", mkts.stream().map(this::toMktVo).toList());
        }
        return Result.ok(data);
    }

    private PostVO toPostVo(Post p) {
        return PostVO.builder()
                .id(p.getId())
                .authorId(p.getAuthorId())
                .content(p.getContent())
                .status(p.getStatus())
                .likeCount(p.getLikeCount())
                .commentCount(p.getCommentCount())
                .circleId(p.getCircleId())
                .createTime(p.getCreateTime())
                .build();
    }

    private UserVO toUserVo(User u) {
        return UserVoAssembler.of(u);
    }

    /**
     * 圈子搜索结果出参(2026-10-21 从三条补齐)。
     *
     * 改版前只回 id / name / memberCount —— 不查关系表,所以 `joined` 恒为假:
     * 我加过的圈在搜索结果里还显示「加入」按钮,点下去是个无效动作。
     * 现在补齐 joined / myRole(与 /circle 列表同口径),外加帖数与最新一帖时间,
     * 搜索结果与列表里的卡片长得一样。
     */
    private List<Map<String, Object>> toCircleVos(List<Circle> circles, long viewer) {
        if (circles == null || circles.isEmpty()) return List.of();
        List<Long> ids = circles.stream().map(Circle::getId).toList();

        // 我在这些圈里的角色:一次查回,避免 N+1。非成员不出现 → myRole=0 / joined=false
        Map<Long, Integer> roleByCircle = new HashMap<>();
        if (viewer > 0) {
            circleMemberMapper.selectList(new LambdaQueryWrapper<com.campus.social.entity.CircleMember>()
                            .in(com.campus.social.entity.CircleMember::getCircleId, ids)
                            .eq(com.campus.social.entity.CircleMember::getUserId, viewer))
                    .forEach(m -> roleByCircle.put(m.getCircleId(),
                            m.getRole() == null ? 3 : m.getRole()));
        }

        // 帖数 + 最新一帖时间:与列表页同一条 SQL,保持卡片副行一致
        Map<Long, Integer> cntByCircle = new HashMap<>();
        Map<Long, String> lastByCircle = new HashMap<>();
        for (Map<String, Object> r : postMapper.postStatsByCircles(ids)) {
            Object cid = r.get("circle_id") != null ? r.get("circle_id") : r.get("circleId");
            if (!(cid instanceof Number n)) continue;
            long id = n.longValue();
            Object cnt = r.get("post_cnt") != null ? r.get("post_cnt") : r.get("postCnt");
            if (cnt instanceof Number c) cntByCircle.put(id, c.intValue());
            Object at = r.get("last_at") != null ? r.get("last_at") : r.get("lastAt");
            if (at instanceof java.sql.Timestamp ts) {
                lastByCircle.put(id, ts.toLocalDateTime().format(TIME_FMT));
            } else if (at instanceof java.time.LocalDateTime t) {
                lastByCircle.put(id, t.format(TIME_FMT));
            }
        }

        List<Map<String, Object>> out = new java.util.ArrayList<>(circles.size());
        for (Circle c : circles) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("memberCount", c.getMemberCount());
            Integer role = roleByCircle.get(c.getId());
            m.put("joined", role != null);
            m.put("myRole", role == null ? 0 : role);
            m.put("postCount", cntByCircle.getOrDefault(c.getId(), 0));
            m.put("lastPostAt", lastByCircle.get(c.getId()));
            out.add(m);
        }
        return out;
    }

    private static final java.time.format.DateTimeFormatter TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Map<String, Object> toMktVo(MktActivity m) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", m.getId());
        vo.put("title", m.getTitle());
        vo.put("content", m.getContent());
        vo.put("category", m.getCategory());
        vo.put("price", m.getPrice());
        vo.put("location", m.getLocation());
        vo.put("userId", m.getUserId());
        vo.put("createTime", m.getCreateTime());
        return vo;
    }
}
