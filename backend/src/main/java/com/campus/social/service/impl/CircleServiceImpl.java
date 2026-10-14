package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.CircleDTO;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.CircleService;
import com.campus.social.service.CircleAdminService;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
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
    /** 官方名保护:建圈撞预留名直接拦(运营后台维护 reserved_circle_name) */
    private final CircleAdminService circleAdminService;

    @Override
    @Transactional
    public long createCircle(long owner, CircleDTO dto) {
        // 官方名保护:撞预留名直接拦(在重名检查之前,文案区分)
        if (circleAdminService.isReservedName(dto.getName())) {
            throw new BusinessException(CIRCLE_NAME_RESERVED, "该圈子名已被系统预留,无法创建");
        }
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
        // 置顶优先:is_top 降序(置顶帖排最前),同状态内按 id 倒序;
        // 置顶/非置顶是两组,游标跨组时用 lastId 作为 id 上界近似(置顶组量小,可接受)
        LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<Post>()
                .eq(Post::getCircleId, circleId)
                .eq(Post::getStatus, 1)
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getIsTop)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1));
        List<Post> rows = postMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Post> page = rows.stream().limit(size).toList();

        List<PostVO> vos = page.stream().map(p -> {
            UserVO avo = UserVoAssembler.of(userMapper.selectById(p.getAuthorId()));
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
                    .isTop(p.getIsTop() != null && p.getIsTop() == 1)
                    .build();
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    private CircleVO toVo(Circle c, long viewerId) {
        UserVO ovo = UserVoAssembler.of(userMapper.selectById(c.getOwnerId()));

        int myRole = 0; // 0=非成员
        boolean joined = false;
        boolean myBanned = false;
        if (viewerId > 0) {
            CircleMember me = circleMemberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                    .eq(CircleMember::getCircleId, c.getId())
                    .eq(CircleMember::getUserId, viewerId));
            if (me != null) {
                joined = true;
                myRole = me.getRole() == null ? 3 : me.getRole();
                myBanned = circleAdminService.isBanned(c.getId(), viewerId);
            }
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
                .myRole(myRole)
                .myBanned(myBanned)
                .build();
    }
}
