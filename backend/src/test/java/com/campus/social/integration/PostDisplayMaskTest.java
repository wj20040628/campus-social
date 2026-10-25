package com.campus.social.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.controller.SearchController;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Post;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.service.CircleService;
import com.campus.social.common.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展示链路打码回归（2026-10-24，配 PostVoAssembler.displayContent 收口）。
 *
 * <p>事故背景：一级敏感词的打码规则只活在 PostVoAssembler.baseVo 里，
 * 搜索结果与圈子帖列表各自裸拼 content，词在那些出口原样泄露。
 * 本测试种一条含一级词的已发布帖，断言**每一个**读出口都打码——
 * 将来新增出口若忘了走 displayContent，这里会红。
 */
@SpringBootTest
@ActiveProfiles("local")
class PostDisplayMaskTest {

    private static final String MASKED_WORD = "代跑"; // 词库 level-1
    private static final String RAW = "明天有人代跑乐跑吗,一次十块";

    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CircleMapper circleMapper;
    @Autowired
    private CircleService circleService;
    @Autowired
    private SearchController searchController;

    private Long circleId;
    private Long postId;

    @BeforeEach
    void seed() {
        Circle c = new Circle();
        c.setName("mask-regression-" + System.currentTimeMillis());
        c.setOwnerId(1L);
        c.setMemberCount(1);
        circleMapper.insert(c);
        circleId = c.getId();

        Post p = new Post();
        p.setAuthorId(1L);
        p.setContent(RAW);
        p.setStatus(1);
        p.setCircleId(circleId);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setViewCount(0);
        p.setHotScore(0.0);
        postMapper.insert(p);
        postId = p.getId();
    }

    @AfterEach
    void cleanup() {
        if (postId != null) {
            postMapper.delete(new LambdaQueryWrapper<Post>().eq(Post::getId, postId));
        }
        if (circleId != null) {
            circleMapper.delete(new LambdaQueryWrapper<Circle>().eq(Circle::getId, circleId));
        }
    }

    @Test
    void circlePostsMaskLevel1Words() {
        var page = circleService.circlePosts(circleId, null, 10, 1L);
        String content = page.getList().get(0).getContent();
        assertTrue(content.contains("**"), "圈子帖列表应打码: " + content);
        assertFalse(content.contains(MASKED_WORD), "圈子帖列表不得出现原词: " + content);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchResultsMaskLevel1Words() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", 1L); // = AuthInterceptor.ATTR_USER_ID
        Result<Map<String, Object>> resp = searchController.search(MASKED_WORD, "post", 20, req);
        // LIKE 兜底必然命中(词在原文里),断言结果里不存在原词
        var posts = (java.util.List<com.campus.social.vo.PostVO>) resp.getData().get("posts");
        assertTrue(posts.size() > 0, "LIKE 兜底应能搜到目标帖");
        for (var vo : posts) {
            assertFalse(vo.getContent().contains(MASKED_WORD),
                    "搜索结果 content 应打码: " + vo.getContent());
        }
    }
}
