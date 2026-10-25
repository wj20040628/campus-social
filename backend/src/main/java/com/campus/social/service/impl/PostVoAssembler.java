package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Like;
import com.campus.social.entity.Post;
import com.campus.social.entity.PostFavorite;
import com.campus.social.entity.PostTopic;
import com.campus.social.entity.Topic;
import com.campus.social.entity.User;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.PostFavoriteMapper;
import com.campus.social.mapper.PostTopicMapper;
import com.campus.social.mapper.TopicMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post → PostVO 的装配器（含作者、话题、点赞态回填）。
 *
 * <p><b>为什么独立成类</b>：Feed 列表、话题页、关注流、热门、我的帖子、帖子详情
 * 六个入口都要走同一套装配规则，且这些规则里各有一条容易踩的坑：
 * <ul>
 *   <li>敏感词替换<b>只在展示层做</b>，DB 存原文——待审/驳回帖保留原文给审核员看；</li>
 *   <li>作者与话题必须<b>批量</b>回填，逐条查是 N+1；</li>
 *   <li>点赞态与收藏态也必须<b>批量</b> IN 查询（2026-10-22 起），
 *       逐条 count 曾让一页 20 条帖子打出 20 次点赞判定，是首屏 SQL 的大头。</li>
 * </ul>
 * 规则散在各处必然出现「同一条帖在列表和详情显示不同」的不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostVoAssembler {

    private static final int TARGET_TYPE_POST = 1;
    private static final int TOPIC_NAME_MAX = 64;

    private final UserMapper userMapper;
    private final LikeMapper likeMapper;
    private final PostFavoriteMapper favoriteMapper;
    private final TopicMapper topicMapper;
    private final PostTopicMapper postTopicMapper;
    private final com.campus.social.mapper.CircleMapper circleMapper;
    private final SensitiveWordFilter sensitive;
    private final ObjectMapper om;

    /**
     * 帖子内容的展示层打码规则（唯一出处，2026-10-24 收口）。
     *
     * <p>status=1（已发布）→ 一级敏感词替换为 *；待审(0)/驳回(2)保留原文
     * （作者看自己的原貌、审核员要看真实文本）。
     *
     * <p>为什么必须是公共方法：此前这条规则只活在本类的 baseVo 里，
     * 搜索结果（SearchController）与圈子帖列表（CircleServiceImpl.circlePosts）
     * 各自裸拼 {@code p.getContent()}，一级词在那两处原样泄露。
     * **任何**把帖子内容发给客户端的出口都必须走这里，别再各写一份。
     */
    public String displayContent(Post p) {
        return (p.getStatus() != null && p.getStatus() == 1)
                ? sensitive.replaceAll(p.getContent())
                : p.getContent();
    }

    /** 单条转换：作者与话题在此一并回填，调用方无需再处理 */
    public PostVO toVo(Post p, long viewerId) {
        PostVO vo = baseVo(p, viewerId);
        vo.setAuthor(authorVo(p.getAuthorId()));
        vo.setTopics(topicsOfPosts(List.of(p.getId())).getOrDefault(p.getId(), List.of()));
        vo.setFavorited(isFavorited(p.getId(), viewerId));
        vo.setCircleName(circleNamesOf(List.of(p.getCircleId() == null ? -1L : p.getCircleId()))
                .get(p.getCircleId()));
        return vo;
    }

    /**
     * 列表批量转换：作者、话题、圈子名、点赞态、收藏态<b>全部批量</b>，无 N+1。
     *
     * <p>先按 viewerId=0 装配「公共骨架」（此时 liked/favorited 恒为 false），
     * 再交给 {@link #fillViewerState} 一次性回填当前用户的点赞/收藏态。
     * 这样拆开是为了让 Feed 首屏缓存能直接存这份公共骨架：
     * 骨架与「谁在看」无关，可以被所有用户共享；用户态必须每次实时查。
     *
     * <p><b>顺序不能反</b>：调用方若先把用户态填进去再缓存，会把 A 用户的
     * 「已赞」状态发给 B 用户。缓存写入必须发生在 fillViewerState 之前。
     */
    public List<PostVO> toVoList(List<Post> posts, long viewerId) {
        List<PostVO> vos = new ArrayList<>(posts.stream().map(p -> baseVo(p, 0L)).toList());
        if (vos.isEmpty()) {
            return vos;
        }
        // 作者批量
        List<Long> authorIds = posts.stream().map(Post::getAuthorId).distinct().toList();
        Map<Long, User> authors = new HashMap<>();
        for (User u : userMapper.selectBatchIds(authorIds)) {
            authors.put(u.getId(), u);
        }
        for (PostVO vo : vos) {
            User a = authors.get(vo.getAuthorId());
            if (a != null) {
                vo.setAuthor(toUserVo(a));
            }
        }
        // 话题批量
        Map<Long, List<String>> topicMap = topicsOfPosts(posts.stream().map(Post::getId).toList());
        for (PostVO vo : vos) {
            vo.setTopics(topicMap.getOrDefault(vo.getId(), List.of()));
        }
        // 圈子名批量:一次 IN 查询覆盖整页,避免「每张卡查一次圈名」的 N+1
        Map<Long, String> circleNames = circleNamesOf(posts.stream().map(Post::getCircleId).toList());
        for (PostVO vo : vos) {
            vo.setCircleName(circleNames.get(vo.getCircleId()));
        }
        // 用户态(点赞+收藏)批量:两次 IN 查询覆盖整页
        fillViewerState(vos, viewerId);
        return vos;
    }

    /**
     * 一次性回填当前用户的点赞态与收藏态。
     *
     * <p>两批查询各一次 IN，取代「每条帖子一次 COUNT」的 N+1：
     * 一页 20 条帖子原本要打 20 次点赞判定，现在 1 次。
     *
     * <p>viewerId<=0（未登录）直接跳过——未登录不该看到「已赞/已收」。
     *
     * <p>方法对传入的 VO 对象做原地赋值，所以调用方必须保证这些 VO
     * 是本次请求独有的对象（缓存路径每次反序列化都会产生新对象，天然满足）。
     */
    public void fillViewerState(List<PostVO> vos, long viewerId) {
        if (vos == null || vos.isEmpty() || viewerId <= 0) {
            return;
        }
        Map<Long, PostVO> byId = new HashMap<>();
        for (PostVO vo : vos) {
            byId.put(vo.getId(), vo);
        }
        List<Long> ids = vos.stream().map(PostVO::getId).toList();
        for (Long id : likeMapper.selectLikedPostIds(viewerId, ids)) {
            PostVO vo = byId.get(id);
            if (vo != null) {
                vo.setLiked(true);
            }
        }
        for (Long id : favoriteMapper.selectFavoritedPostIds(viewerId, ids)) {
            PostVO vo = byId.get(id);
            if (vo != null) {
                vo.setFavorited(true);
            }
        }
    }

    private PostVO baseVo(Post p, long viewerId) {
        JsonNode media = null;
        if (p.getMedia() != null && !p.getMedia().isBlank()) {
            try {
                media = om.readTree(p.getMedia());
            } catch (Exception ignored) {
                // media 解析失败不阻塞帖子展示:退化成无图,内容照常显示
            }
        }
        /*
         * 展示层替换:规则唯一出处见 displayContent() 的 javadoc——
         * 所有把帖子内容发给客户端的出口都必须走它。
         */
        String outContent = displayContent(p);
        return PostVO.builder()
                .id(p.getId())
                .authorId(p.getAuthorId())
                .content(outContent)
                .media(media)
                .status(p.getStatus())
                .likeCount(p.getLikeCount())
                .commentCount(p.getCommentCount())
                .favoriteCount(p.getFavoriteCount() == null ? 0 : p.getFavoriteCount())
                .viewCount(p.getViewCount())
                .hotScore(p.getHotScore())
                .circleId(p.getCircleId())
                .createTime(p.getCreateTime())
                .liked(isLiked(p.getId(), viewerId))
                .favorited(false) // 列表走批量回填、详情走 toVo 单查,此处只给默认值
                .isTop(p.getIsTop() != null && p.getIsTop() == 1)
                .build();
    }

    private boolean isLiked(long postId, long viewerId) {
        if (viewerId <= 0) {
            return false; // 未登录不该看到「已赞」
        }
        return likeMapper.selectCount(new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, viewerId)
                .eq(Like::getTargetType, TARGET_TYPE_POST)
                .eq(Like::getTargetId, postId)) > 0;
    }

    /** 单条详情用:未登录恒 false,登录才查 */
    private boolean isFavorited(long postId, long viewerId) {
        if (viewerId <= 0) {
            return false;
        }
        return favoriteMapper.selectCount(new LambdaQueryWrapper<PostFavorite>()
                .eq(PostFavorite::getUserId, viewerId)
                .eq(PostFavorite::getPostId, postId)) > 0;
    }

    /**
     * 批量取圈子名：circleIds → circle，一次查询。
     *
     * <p><b>已解散/被封禁的圈不返回</b>：{@code circle.status=1} 同时表示「解散」
     * 与「被封禁」，两种都不该让用户点进去。返回 null（而非空串）可以让前端
     * 用「有无圈子名」区分「广场帖」和「圈已不可用」，两种情况都表现为不渲染标签。
     *
     * <p>{@code selectBatchIds} 只自动过滤 {@code @TableLogic} 的 is_deleted，
     * <b>不会</b>过滤 status —— 所以这里必须显式判 status，否则解散的圈
     * 仍会把名字下发出去。
     */
    private Map<Long, String> circleNamesOf(List<Long> circleIds) {
        if (circleIds == null || circleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 去重 + 剔除 null（广场帖 circleId 为空）
        List<Long> ids = circleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new HashMap<>();
        for (Circle c : circleMapper.selectBatchIds(ids)) {
            // status=1（解散/封禁）不下发名字，与 CircleServiceImpl.detail 的可见口径一致
            if (c.getStatus() != null && c.getStatus() == 1) {
                continue;
            }
            if (c.getName() != null) {
                result.put(c.getId(), c.getName());
            }
        }
        return result;
    }

    private UserVO authorVo(Long authorId) {
        // null = 作者不存在或已注销。帖子仍在但作者卡渲染为空，属可接受降级
        if (authorId == null) {
            return null;
        }
        User a = userMapper.selectById(authorId);
        return a == null ? null : toUserVo(a);
    }

    private UserVO toUserVo(User u) {
        return UserVoAssembler.of(u);
    }

    /**
     * 批量取话题名：postIds → post_topic → topic，两次查询拿全。
     *
     * <p>逐帖查是典型 N+1，一页 20 条帖就是 20 次查询。
     */
    public Map<Long, List<String>> topicsOfPosts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PostTopic> links = postTopicMapper.listByPostIds(postIds);
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> idToName = new HashMap<>();
        for (Topic t : topicMapper.selectBatchIds(links.stream().map(PostTopic::getTopicId).distinct().toList())) {
            idToName.put(t.getId(), t.getName());
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (PostTopic l : links) {
            String name = idToName.get(l.getTopicId());
            if (name != null) {
                result.computeIfAbsent(l.getPostId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }
}
