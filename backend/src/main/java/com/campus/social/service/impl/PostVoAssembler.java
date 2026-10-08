package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Like;
import com.campus.social.entity.Post;
import com.campus.social.entity.PostTopic;
import com.campus.social.entity.Topic;
import com.campus.social.entity.User;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.PostTopicMapper;
import com.campus.social.mapper.TopicMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
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
 *   <li>点赞态必须<b>逐条</b>查（没有批量接口），这是 Feed 页仅存的 N+1 点。</li>
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
    private final TopicMapper topicMapper;
    private final PostTopicMapper postTopicMapper;
    private final SensitiveWordFilter sensitive;
    private final ObjectMapper om;

    /** 单条转换：作者与话题在此一并回填，调用方无需再处理 */
    public PostVO toVo(Post p, long viewerId) {
        PostVO vo = baseVo(p, viewerId);
        vo.setAuthor(authorVo(p.getAuthorId()));
        vo.setTopics(topicsOfPosts(List.of(p.getId())).getOrDefault(p.getId(), List.of()));
        return vo;
    }

    /**
     * 列表批量转换：作者与话题都走批量查询。
     *
     * <p>只有点赞态是逐条查的（{@code likeMapper} 无批量接口）。
     * 要消掉这个 N+1 得给 LikeMapper 加「查我对这批帖的点赞集合」，
     * 那是跨模块改动，本轮拆分不扩大爆炸半径。
     */
    public List<PostVO> toVoList(List<Post> posts, long viewerId) {
        List<PostVO> vos = posts.stream().map(p -> baseVo(p, viewerId)).toList();
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
        return vos;
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
        // 展示层替换:DB 存原文,公开展示(status=1)时把命中词替换成 *,
        // 待审(0)/驳回(2)帖保留原文(作者/审核员看原貌)
        String outContent = (p.getStatus() != null && p.getStatus() == 1)
                ? sensitive.replaceAll(p.getContent())
                : p.getContent();
        return PostVO.builder()
                .id(p.getId())
                .authorId(p.getAuthorId())
                .content(outContent)
                .media(media)
                .status(p.getStatus())
                .likeCount(p.getLikeCount())
                .commentCount(p.getCommentCount())
                .viewCount(p.getViewCount())
                .hotScore(p.getHotScore())
                .circleId(p.getCircleId())
                .createTime(p.getCreateTime())
                .liked(isLiked(p.getId(), viewerId))
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

    private UserVO authorVo(Long authorId) {
        if (authorId == null) {
            return null;
        }
        User a = userMapper.selectById(authorId);
        return a == null ? null : toUserVo(a);
    }

    private UserVO toUserVo(User u) {
        return UserVO.builder()
                .id(u.getId())
                .username(u.getUsername())
                .nickname(u.getNickname() != null ? u.getNickname() : u.getUsername())
                .avatarUrl(u.getAvatarUrl())
                .grade(u.getGrade())
                .dept(u.getDept())
                .build();
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
