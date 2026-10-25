package com.campus.social.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.Result;
import com.campus.social.controller.SearchController;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Post;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.service.CircleService;
import com.campus.social.service.impl.PostFeedQueryService;
import com.campus.social.service.impl.PostVoAssembler;
import com.campus.social.vo.PostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 树洞匿名帖回归（2026-10-25，配 is_anonymous 落地）。
 *
 * <p>脱敏规则与 displayContent 同一性质：规则只活在 PostVoAssembler 会漏。
 * 本测试断言**每一个**读出口（feed 列表/详情/搜索/圈子）对匿名帖都不下发
 * author 与 authorId（authorId 保留可被 /user/{id} 反查主页，等于实名），
 * 且他人主页帖子列表整条排除匿名帖（列表本身即泄露）。
 */
@SpringBootTest
@ActiveProfiles("local")
class PostAnonymousTest {

    private static final String KEYWORD = "树洞回归" + System.nanoTime() % 10000;
    private static final long AUTHOR = 1L;
    private static final long OTHER = 2L;

    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CircleMapper circleMapper;
    @Autowired
    private PostVoAssembler assembler;
    @Autowired
    private PostFeedQueryService feedQuery;
    @Autowired
    private CircleService circleService;
    @Autowired
    private SearchController searchController;

    private Long anonId;
    private Long plainId;
    private Long circleId;

    @BeforeEach
    void seed() {
        anonId = insertPost(true);
        plainId = insertPost(false);

        Circle c = new Circle();
        c.setName("anon-regression-" + System.currentTimeMillis());
        c.setOwnerId(AUTHOR);
        c.setMemberCount(1);
        circleMapper.insert(c);
        circleId = c.getId();

        Post inCircle = buildPost(true);
        inCircle.setCircleId(circleId);
        postMapper.insert(inCircle);
        anonId = inCircle.getId(); // 圈内匿名帖作为主断言对象(列表+圈子两个出口都能覆盖)
    }

    private Long insertPost(boolean anon) {
        Post p = buildPost(anon);
        postMapper.insert(p);
        return p.getId();
    }

    private Post buildPost(boolean anon) {
        Post p = new Post();
        p.setAuthorId(AUTHOR);
        p.setContent(KEYWORD + (anon ? " 匿名样本" : " 实名样本"));
        p.setStatus(1);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setViewCount(0);
        p.setHotScore(0.0);
        p.setIsAnonymous(anon ? 1 : 0);
        return p;
    }

    @AfterEach
    void cleanup() {
        postMapper.delete(new LambdaQueryWrapper<Post>().like(Post::getContent, KEYWORD));
        if (circleId != null) {
            circleMapper.delete(new LambdaQueryWrapper<Circle>().eq(Circle::getId, circleId));
        }
    }

    @Test
    void listAssemblyStripsAuthorForAnonPosts() {
        Post anon = postMapper.selectById(anonId);
        Post plain = postMapper.selectById(plainId);
        List<PostVO> vos = assembler.toVoList(List.of(anon, plain), OTHER);
        PostVO anonVo = vos.get(0);
        assertTrue(anonVo.isAnonymous());
        assertNull(anonVo.getAuthorId(), "匿名帖 authorId 必须抹掉");
        assertNull(anonVo.getAuthor(), "匿名帖 author 卡必须为 null");
        assertFalse(anonVo.isMine(), "他人视角 mine=false");
        PostVO plainVo = vos.get(1);
        assertFalse(plainVo.isAnonymous());
        assertEquals(AUTHOR, plainVo.getAuthorId());
        // 匿名标记在公共骨架(viewerId=0,即 Feed 缓存视角)也必须存在
        PostVO skeleton = assembler.toVoList(List.of(anon), 0L).get(0);
        assertTrue(skeleton.isAnonymous());
    }

    @Test
    void detailKeepsMineForAuthorButStripsIdentity() {
        Post anon = postMapper.selectById(anonId);
        PostVO self = feedQuery.toDetailVo(anon, AUTHOR);
        assertTrue(self.isMine(), "作者本人看详情 mine=true(编辑/删除入口靠它)");
        assertNull(self.getAuthorId());
        PostVO other = feedQuery.toDetailVo(anon, OTHER);
        assertFalse(other.isMine());
        assertNull(other.getAuthorId());
    }

    @Test
    void otherUserProfileExcludesAnonPosts() {
        var otherView = feedQuery.myPosts(AUTHOR, OTHER, null, 10);
        assertTrue(otherView.getList().stream().noneMatch(PostVO::isAnonymous),
                "他人主页不得出现作者的匿名帖");
        var selfView = feedQuery.myPosts(AUTHOR, AUTHOR, null, 10);
        assertTrue(selfView.getList().stream().anyMatch(vo -> vo.getId().equals(anonId)),
                "本人视角应保留自己的匿名帖");
    }

    @Test
    void searchAndCirclePostsStripAuthorForAnon() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", OTHER);
        Result<Map<String, Object>> resp = searchController.search(KEYWORD, "post", 20, req);
        @SuppressWarnings("unchecked")
        List<PostVO> posts = (List<PostVO>) resp.getData().get("posts");
        assertTrue(posts.size() > 0, "LIKE 兜底应能搜到匿名帖");
        for (PostVO vo : posts) {
            if (vo.isAnonymous()) {
                assertNull(vo.getAuthorId(), "搜索结果不得下发匿名帖 authorId: " + vo.getId());
            } else {
                assertEquals(AUTHOR, vo.getAuthorId());
            }
        }

        var circlePage = circleService.circlePosts(circleId, null, 10, OTHER);
        for (PostVO vo : circlePage.getList()) {
            assertNull(vo.getAuthorId(), "圈子列表不得下发匿名帖 authorId: " + vo.getId());
        }
    }
}
