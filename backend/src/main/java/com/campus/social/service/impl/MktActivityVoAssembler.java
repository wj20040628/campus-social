package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 集市活动 VO 装配（2026-11-01 从 MktActivityServiceImpl 拆出）。
 *
 * <p>只做「实体 → 展示对象」与批量取用户，不含权限/配额判断；
 * 广场列表一次取整页用户，避免逐行查库。
 */
@Component
@RequiredArgsConstructor
class MktActivityVoAssembler {

    private final UserMapper userMapper;

    MktStatus statusOf(MktActivity a) {
        boolean isFull = Integer.valueOf(1).equals(a.getIsFull());
        boolean isEnable = Integer.valueOf(1).equals(a.getIsEnable());
        boolean expired = a.getExpireTime() == null || !a.getExpireTime().isAfter(LocalDateTime.now());
        return MktStatus.derive(isFull, isEnable, expired);
    }

    MktActivityVO toVo(MktActivity a) {
        return MktActivityVO.builder()
                .id(a.getId())
                .userId(a.getUserId())
                .category(a.getCategory())
                .subTag(a.getSubTag())
                .title(a.getTitle())
                .content(a.getContent())
                .targetNum(a.getTargetNum())
                .price(a.getPrice())
                .location(a.getLocation())
                .isEnable(a.getIsEnable())
                .expireTime(a.getExpireTime())
                .isFull(a.getIsFull())
                .createTime(a.getCreateTime())
                .lastLaunchTime(a.getLastLaunchTime())
                .mainText(MktActivityRules.derivedTitle(a.getCategory(), a.getSubTag()))
                .build();
    }

    /** 广场/详情行：填发布者昵称/头像（脱敏由前端 displayName 兜底，此处取原昵称）。 */
    void fillPublisher(MktActivityVO vo, User u) {
        if (vo == null || u == null) {
            return;
        }
        vo.setNickname(u.getNickname());
        vo.setAvatarUrl(u.getAvatarUrl());
    }

    /**
     * 把一页广场实体转成带发布者的 VO 列表 + 游标。
     * 不填 status（广场行不显示 4 态，那是「我的发布」的事）。
     */
    CursorPage<MktActivityVO> toPlazaPage(List<MktActivity> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<MktActivity> page = rows.stream().limit(size).toList();
        Map<Long, User> users = usersByIds(page.stream().map(MktActivity::getUserId).toList());

        List<MktActivityVO> vos = page.stream().map(a -> {
            MktActivityVO vo = toVo(a);
            fillPublisher(vo, users.get(a.getUserId()));
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /** 批量取用户消 N+1；广场分页最多 100 条，一次性取。 */
    Map<Long, User> usersByIds(List<Long> ids) {
        Set<Long> uniq = new HashSet<>(ids);
        uniq.remove(null);
        if (uniq.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectByIds(uniq);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
