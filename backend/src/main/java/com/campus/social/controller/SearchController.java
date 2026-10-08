package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Post;
import com.campus.social.entity.User;
import com.campus.social.mapper.CircleMapper;
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

    public SearchController(PostMapper postMapper, UserMapper userMapper, CircleMapper circleMapper) {
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.circleMapper = circleMapper;
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
            data.put("circles", circles.stream().map(this::toCircleVo).toList());
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

    private Map<String, Object> toCircleVo(Circle c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("memberCount", c.getMemberCount());
        return m;
    }
}
