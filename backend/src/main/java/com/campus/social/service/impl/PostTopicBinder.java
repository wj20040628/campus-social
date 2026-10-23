package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.PostTopic;
import com.campus.social.entity.Topic;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.PostTopicMapper;
import com.campus.social.mapper.TopicMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 帖子话题绑定与 media 校验。
 *
 * <p><b>为什么独立</b>：话题绑定有三处调用（发帖、编辑、装配器回读），
 * 且「编辑时先删旧再绑新」这组操作必须和 post_count 的增减严格配对——
 * 拆开后配对逻辑集中在一处，不会出现「软删了但没减计数」这种只影响统计的暗 bug。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostTopicBinder {

    /**
     * 话题名长度上限。
     *
     * <p>原为 64（跟着 topic.name 列的 VARCHAR(64) 定）。收紧到 15 是**展示侧**的要求：
     * 话题在卡片上是胶囊、在详情页是一行 chips，15 字以内才放得下；
     * 40 多字的「#11111111…」在 375px 下会把整个卡片撑破（前端已改为强制折行兜底，
     * 但那是补救，不该让这种话题进得来）。
     *
     * <p>15 字对真实话题足够：校园场景的话题是「考研」「美食」「23届网安」这类短词，
     * 而话题的价值恰恰在于短——太长就变成标题了。
     *
     * <p><b>存量数据（旧上限 64 时期入库的长话题）仍可能存在</b>，
     * 所以前端必须保留折行兜底，不能假定服务端一定给了短话题。
     */
    private static final int TOPIC_NAME_MAX = 15;

    private final TopicMapper topicMapper;
    private final PostTopicMapper postTopicMapper;
    private final ObjectMapper om;

    /**
     * 绑定帖子话题：逐个 upsert topic（ON DUPLICATE 累加 post_count）+ 写 post_topic（幂等键 uk_pt）。
     *
     * <p>超长话题名直接丢弃而不是报错：话题是附属信息，
     * 因为一个超长话题让整篇帖子发不出去不划算。
     */
    public void bind(long postId, List<String> topicNames) {
        if (topicNames == null || topicNames.isEmpty()) {
            return;
        }
        // 去重 + 去空 + 去两侧 # 与空白
        List<String> clean = topicNames.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().replaceAll("^#+", "").replaceAll("#+$", "").trim())
                .filter(n -> !n.isBlank())
                .distinct()
                .toList();
        for (String name : clean) {
            if (name.length() > TOPIC_NAME_MAX) {
                continue; // 话题名超长丢弃,防脏
            }
            topicMapper.upsertByName(name);
            Topic t = topicMapper.selectOne(new LambdaQueryWrapper<Topic>().eq(Topic::getName, name));
            if (t == null) {
                continue;
            }
            long cnt = postTopicMapper.selectCount(new LambdaQueryWrapper<PostTopic>()
                    .eq(PostTopic::getPostId, postId)
                    .eq(PostTopic::getTopicId, t.getId()));
            if (cnt == 0) {
                PostTopic pt = new PostTopic();
                pt.setPostId(postId);
                pt.setTopicId(t.getId());
                postTopicMapper.insert(pt);
            }
        }
        log.info("post topics bound: post={}, topics={}", postId, clean);
    }

    public List<Long> topicIdsOf(long postId) {
        return postTopicMapper.topicIdsByPost(postId);
    }

    /** 软删该帖的全部话题关联。调用方必须紧接 {@link #decrementCounts} 减计数 */
    public void softDeleteOf(long postId) {
        postTopicMapper.softDeleteByPost(postId);
    }

    /**
     * 回退话题的 post_count。
     *
     * <p>与 {@link #softDeleteOf} 配对使用。计数只影响话题热度排序，
     * 漏减不会让功能出错但会让热门话题长期虚高，所以单独成方法名以示配对关系。
     */
    public void decrementCounts(List<Long> topicIds) {
        for (Long tid : topicIds) {
            topicMapper.decrementPostCount(tid);
        }
    }

    /**
     * 校验 media 是合法 JSON 数组，非法直接拒发。
     *
     * <p>不校验的话，脏 JSON 入库后前端 {@code JSON.parse} 崩溃，
     * 整条帖子卡片渲染不出来——而后端自己看不出问题在哪。
     */
    public JsonNode parseMedia(String mediaJson) {
        if (mediaJson == null || mediaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = om.readTree(mediaJson);
            if (!node.isArray()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "media 必须是数组");
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "media 格式非法");
        }
    }
}
