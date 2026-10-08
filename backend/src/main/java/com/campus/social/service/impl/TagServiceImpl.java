package com.campus.social.service.impl;

import com.campus.social.config.CampusProperties;
import com.campus.social.entity.InterestTag;
import com.campus.social.entity.UserTag;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.InterestTagMapper;
import com.campus.social.mapper.UserTagMapper;
import com.campus.social.service.TagService;
import com.campus.social.vo.TagGroupVO;
import com.campus.social.vo.TagItemVO;
import com.campus.social.vo.TagTreeVO;
import com.campus.social.vo.UserTagsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.campus.social.common.ResultCode.TAG_NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 兴趣标签实现。
 *
 * <p>核心是 {@link #replaceTagsInTx} 的「全量覆盖」写法：
 * 先物理删掉本人全部 user_tag 行，再批量 upsert。
 *
 * <p>为什么必须物理删而不是软删：{@code uk_user_tag(user_id, tag_id)} 不含 is_deleted，
 * 软删留行会让唯一键继续锁住；第二次覆盖同一标签时 upsert 虽不报错，
 * 但 use_count 的 ±1 会与残留行对不上，计数将永久性错位。
 * 这与 RelationServiceImpl / ActivityServiceImpl 的处理方式一致。
 *
 * <p>use_count 的增减一律 SET use_count = use_count ± 1，绝不读改写：
 * 「先 select 再 set」在并发下必然丢计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final InterestTagMapper tagMapper;
    private final UserTagMapper userTagMapper;
    private final CampusProperties campus;

    /** 标签分类中文名。写死而非建字典表：5 个固定值，建表 + 联表纯属过度设计 */
    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
            1, "兴趣",
            2, "游戏",
            3, "运动",
            4, "学习",
            5, "生活"
    );

    /** 分组顺序，与分类编号一致 */
    private static final List<Integer> CATEGORY_ORDER = List.of(1, 2, 3, 4, 5);

    @Override
    public TagTreeVO listTree(Integer category) {
        List<InterestTag> all = tagMapper.selectAllOrdered(category);
        Map<Integer, List<TagItemVO>> grouped = new LinkedHashMap<>();
        for (InterestTag t : all) {
            grouped.computeIfAbsent(t.getCategory(), k -> new ArrayList<>()).add(toVo(t));
        }
        List<TagGroupVO> groups = new ArrayList<>();
        for (Integer cat : CATEGORY_ORDER) {
            List<TagItemVO> tags = grouped.remove(cat);
            if (tags == null || tags.isEmpty()) {
                // 该分类无标签时不返回空分组——空分组会让前端渲染出一个只有标题没有内容的分组
                continue;
            }
            groups.add(TagGroupVO.builder()
                    .category(cat)
                    .categoryName(categoryName(cat))
                    .tags(tags)
                    .build());
        }
        return new TagTreeVO(groups, all.size());
    }

    @Override
    public List<TagItemVO> listMine(long userId) {
        List<Long> tagIds = userTagMapper.selectTagIdsByUser(userId);
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return listByIds(tagIds);
    }

    @Override
    public List<TagItemVO> listByUser(long userId) {
        return listMine(userId);
    }

    @Override
    @Transactional
    public UserTagsVO replaceMine(long userId, List<Long> tagIds) {
        if (tagIds != null && tagIds.size() > campus.getMaxTags()) {
            throw new BusinessException(UNPROCESSABLE,
                    "兴趣标签最多选择 " + campus.getMaxTags() + " 个");
        }
        List<Long> valid = validateTagIds(tagIds);
        // 注意:此处不能依赖 replaceTagsInTx 上的 @Transactional——
        // 同类内部自调用不走 Spring 代理,注解会失效。事务边界必须挂在本方法上。
        replaceTagsInTx(userId, valid);
        return UserTagsVO.of(userId, listByIds(valid));
    }

    @Override
    @Transactional
    public void replaceTagsInTx(long userId, List<Long> tagIds) {
        List<Long> old = userTagMapper.selectTagIdsByUser(userId);
        List<Long> target = tagIds == null ? List.of() : tagIds;

        // 1. 物理删清场（必须，否则唯一键锁行、计数错位）
        userTagMapper.hardDeleteAllByUser(userId);

        // 2. 批量插入
        for (Long tagId : target) {
            userTagMapper.upsert(userId, tagId);
        }

        // 3. 计数对齐：新增的 +1，不再选的 -1
        Set<Long> oldSet = new LinkedHashSet<>(old);
        Set<Long> newSet = new LinkedHashSet<>(target);
        List<Long> toAdd = newSet.stream().filter(id -> !oldSet.contains(id)).toList();
        List<Long> toRemove = oldSet.stream().filter(id -> !newSet.contains(id)).toList();
        if (!toAdd.isEmpty()) {
            tagMapper.adjustUseCountBatch(toAdd, 1);
        }
        if (!toRemove.isEmpty()) {
            tagMapper.adjustUseCountBatch(toRemove, -1);
        }
        log.info("user tags replaced: user={}, old={}, new={}", userId, oldSet.size(), newSet.size());
    }

    @Override
    public List<TagItemVO> onboardingCandidates(int total) {
        List<InterestTag> ordered = tagMapper.selectAllOrdered(null);
        // 每组配额向上取整，保证总量够用；分类数用 CATEGORY_ORDER.size() 而非写死 5
        int perCategory = Math.max(1, (int) Math.ceil((double) total / CATEGORY_ORDER.size()));
        Map<Integer, Integer> perCatCount = new LinkedHashMap<>();
        List<TagItemVO> out = new ArrayList<>();
        for (InterestTag t : ordered) {
            int used = perCatCount.getOrDefault(t.getCategory(), 0);
            if (used >= perCategory || out.size() >= total) {
                continue;
            }
            perCatCount.put(t.getCategory(), used + 1);
            out.add(toVo(t));
        }
        return out;
    }

    @Override
    public List<TagItemVO> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<InterestTag> rows = tagMapper.selectBatchIds(ids);
        Map<Long, InterestTag> byId = rows.stream()
                .collect(Collectors.toMap(InterestTag::getId, x -> x, (a, b) -> a));
        // 保持传入顺序：前端展示顺序应与用户选择顺序一致
        List<TagItemVO> out = new ArrayList<>();
        for (Long id : ids) {
            InterestTag t = byId.get(id);
            if (t != null) {
                out.add(toVo(t));
            }
        }
        return out;
    }

    @Override
    public List<UserTag> listUserTags(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userTagMapper.selectByUserIds(userIds);
    }

    @Override
    public List<Long> validateTagIds(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        // 去重但保持顺序：前端可能重复提交同一个 id
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(tagIds));
        List<InterestTag> found = tagMapper.selectBatchIds(distinct);
        if (found.size() != distinct.size()) {
            Set<Long> foundIds = found.stream().map(InterestTag::getId).collect(Collectors.toSet());
            List<Long> missing = distinct.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new BusinessException(TAG_NOT_FOUND,
                    "存在无效的兴趣标签 id: " + missing);
        }
        return distinct;
    }

    @Override
    public String categoryName(Integer category) {
        return CATEGORY_NAMES.getOrDefault(category, "其他");
    }

    @Override
    public TagItemVO toVo(InterestTag tag) {
        return TagItemVO.builder()
                .id(tag.getId())
                .name(tag.getName())
                .category(tag.getCategory())
                .useCount(tag.getUseCount())
                .build();
    }
}
