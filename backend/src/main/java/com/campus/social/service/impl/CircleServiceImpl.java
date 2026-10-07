package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.CircleDTO;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.CircleService;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircleServiceImpl implements CircleService {

    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public long createCircle(long owner, CircleDTO dto) {
        // 名称唯一(逻辑删除外)
        LambdaQueryWrapper<Circle> qw = new LambdaQueryWrapper<Circle>()
                .eq(Circle::getName, dto.getName());
        if (circleMapper.selectCount(qw) > 0) {
            throw new BusinessException(CONFLICT, "圈子名称已存在");
        }
        Circle c = new Circle();
        c.setName(dto.getName());
        c.setOwnerId(owner);
        c.setMemberCount(1);
        c.setStatus(0);
        circleMapper.insert(c);

        // 创建者 role=1(creator)
        CircleMember cm = new CircleMember();
        cm.setCircleId(c.getId());
        cm.setUserId(owner);
        cm.setRole(1);
        circleMemberMapper.insert(cm);

        log.info("circle created: id={}, owner={}", c.getId(), owner);
        return c.getId();
    }

    @Override
    public CursorPage<CircleVO> list(long me, Long lastId, int size) {
        LambdaQueryWrapper<Circle> qw = new LambdaQueryWrapper<Circle>()
                .eq(Circle::getStatus, 0)
                .lt(lastId != null, Circle::getId, lastId)
                .orderByDesc(Circle::getId)
                .last("LIMIT " + (size + 1));
        List<Circle> rows = circleMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Circle> page = rows.stream().limit(size).toList();
        List<CircleVO> vos = page.stream().map(c -> toVo(c, me)).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CircleVO detail(long circleId, long viewerId) {
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
        }
        return toVo(c, viewerId);
    }

    @Override
    @Transactional
    public void join(long circleId, long userId) {
        Circle c = circleMapper.selectById(circleId);
        if (c == null) {
            throw new BusinessException(NOT_FOUND, "圈子不存在");
        }
        // 幂等:已加入直接返回
        LambdaQueryWrapper<CircleMember> qw = new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId);
        if (circleMemberMapper.selectCount(qw) > 0) {
            log.info("join idempotent skip: {} -> {}", userId, circleId);
            return;
        }
        CircleMember cm = new CircleMember();
        cm.setCircleId(circleId);
        cm.setUserId(userId);
        cm.setRole(3); // member
        circleMemberMapper.insert(cm);
        circleMapper.adjustMemberCount(circleId, 1);
    }

    @Override
    @Transactional
    public void leave(long circleId, long userId) {
        Circle c = circleMapper.selectById(circleId);
        if (c == null) {
            throw new BusinessException(NOT_FOUND, "圈子不存在");
        }
        if (c.getOwnerId().equals(userId)) {
            throw new BusinessException(UNPROCESSABLE, "圈子创建者不可退出");
        }
        LambdaQueryWrapper<CircleMember> qw = new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId)
                .eq(CircleMember::getRole, 3);
        List<CircleMember> rows = circleMemberMapper.selectList(qw);
        if (rows.isEmpty()) {
            return; // 未加入,幂等
        }
        // 物理删(绕过 @TableLogic 软删,否则残留行锁住 uk_cm,再入会撞唯一键)
        circleMemberMapper.hardDelete(circleId, userId, 3);
        circleMapper.adjustMemberCount(circleId, -1);
    }

    @Override
    public CursorPage<PostVO> circlePosts(long circleId, Long lastId, int size, long viewerId) {
        LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<Post>()
                .eq(Post::getCircleId, circleId)
                .eq(Post::getStatus, 1)
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1));
        List<Post> rows = postMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();

        List<PostVO> vos = page.stream().map(p -> {
            User author = userMapper.selectById(p.getAuthorId());
            UserVO avo = author == null ? null : UserVO.builder()
                    .id(author.getId())
                    .username(author.getUsername())
                    .nickname(author.getNickname() != null ? author.getNickname() : author.getUsername())
                    .avatarUrl(author.getAvatarUrl())
                    .build();
            return PostVO.builder()
                    .id(p.getId())
                    .authorId(p.getAuthorId())
                    .author(avo)
                    .content(p.getContent())
                    .media(null)
                    .status(p.getStatus())
                    .likeCount(p.getLikeCount())
                    .commentCount(p.getCommentCount())
                    .hotScore(p.getHotScore())
                    .circleId(p.getCircleId())
                    .createTime(p.getCreateTime())
                    .build();
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    private CircleVO toVo(Circle c, long viewerId) {
        User owner = userMapper.selectById(c.getOwnerId());
        UserVO ovo = owner == null ? null : UserVO.builder()
                .id(owner.getId())
                .username(owner.getUsername())
                .nickname(owner.getNickname() != null ? owner.getNickname() : owner.getUsername())
                .avatarUrl(owner.getAvatarUrl())
                .build();

        boolean joined = false;
        if (viewerId > 0) {
            LambdaQueryWrapper<CircleMember> qw = new LambdaQueryWrapper<CircleMember>()
                    .eq(CircleMember::getCircleId, c.getId())
                    .eq(CircleMember::getUserId, viewerId);
            joined = circleMemberMapper.selectCount(qw) > 0;
        }
        return CircleVO.builder()
                .id(c.getId())
                .name(c.getName())
                .ownerId(c.getOwnerId())
                .owner(ovo)
                .memberCount(c.getMemberCount())
                .status(c.getStatus())
                .createTime(c.getCreateTime())
                .joined(joined)
                .build();
    }
}
