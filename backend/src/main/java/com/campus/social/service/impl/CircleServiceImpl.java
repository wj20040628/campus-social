package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.CircleDTO;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.CircleService;
import com.campus.social.service.CircleAdminService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.PostVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.util.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
    /** 敏感词 DFA:建圈名命中 level-2(拒发)直接 422;level-1(替换)也拒,圈名是永久标识,不允许打码入库 */
    private final SensitiveWordFilter sensitive;
    /** 展示层打码规则的唯一出处(2026-10-24:圈子帖列表此前裸拼原文,一级词泄露) */
    private final PostVoAssembler postVoAssembler;
    /** 建圈配额(2026-10-25):信誉分档 1/2/3,解散释放名额 */
    private final ReviewTrustService trustService;
    /** 行为信任门槛：账号满 3 天 + 信誉 60（校园信息不参与） */
    private final CampusAccessService access;

    @Override
    @Transactional
    public long createCircle(long owner, CircleDTO dto) {
        // 行为门槛在配额之前：账号太新/信誉不足时，改名字也没用，先把结论给出来
        access.requireCreateCircle(owner);
        int quota = trustService.creationQuota(owner);
        if (quota <= 0 || circleMapper.countActiveOwnedBy(owner) >= quota) {
            throw new BusinessException(CIRCLE_LIMIT_REACHED,
                    "已达当前信誉可建的圈子数上限(" + quota + " 个),提升信誉分后自动扩容");
        }
        // 归一化:trim + 全角转半角(「考研１」与「考研1」不应被视为两个名字)
        String name = normalizeName(dto.getName());
        // 长度 3-7 + 必须含至少 1 个汉字(纯数字/纯字母/纯符号都拒)。
        // 用负向先行 (?![0-9a-zA-Z._-]*$) 排除纯非汉字串,允许「23届网安」这种混合
        if (!name.matches("^(?![0-9a-zA-Z._-]*$)[\\u4e00-\\u9fa5a-zA-Z0-9._-]{3,7}$")) {
            throw new BusinessException(CIRCLE_NAME_INVALID, "圈子名需含汉字(可搭配字母/数字),不能是纯数字/纯字母");
        }
        // 敏感词拦截:level-2 拒发直接 422;level-1 也拒(圈名是永久标识,
        // 不允许打码入库——被替换成 * 的圈名后续没法展示/搜索)
        if (sensitive.containsBlock(name) || sensitive.containsReplace(name)) {
            throw new BusinessException(SENSITIVE_WORD_BLOCKED, "圈子名包含违规词,换一个再试");
        }
        // 官方名保护:撞预留名直接拦(在重名检查之前,文案区分)
        if (circleAdminService.isReservedName(name)) {
            throw new BusinessException(CIRCLE_NAME_RESERVED, "该圈子名已被系统预留,无法创建");
        }
        // 名称唯一(逻辑删除外)。用归一化后的 name,保证「 考研圈」与「考研圈」不被当两个
        LambdaQueryWrapper<Circle> qw = new LambdaQueryWrapper<Circle>()
                .eq(Circle::getName, name);
        if (circleMapper.selectCount(qw) > 0) {
            throw new BusinessException(CONFLICT, "圈子名称已存在");
        }
        Circle c = new Circle();
        c.setName(name);
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
        fillPostStats(vos);
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        // total:可见圈子总数(不含游标条件)。给「N 个圈」用 —— 已加载条数不是总数
        Long total = circleMapper.selectCount(new LambdaQueryWrapper<Circle>()
                .eq(Circle::getStatus, 0));
        return new CursorPage<>(vos, nextCursor, hasMore, total);
    }

    @Override
    public CircleVO detail(long circleId, long viewerId) {
        Circle c = circleMapper.selectById(circleId);
        if (c == null || c.getStatus() == 1) {
            throw new BusinessException(NOT_FOUND, "圈子不存在或已封禁");
        }
        CircleVO vo = toVo(c, viewerId);
        fillPostStats(java.util.Collections.singletonList(vo));
        return vo;
    }

    /**
     * 批量回填帖数与最新一帖时间(列表副行「最近动态」用)。
     *
     * <p>一次 SQL 查回整页圈子,不做 N+1。聚合结果没有对应实体,MyBatis 返回
     * Map 且键是**列别名原样**(map-underscore-to-camel-case 只作用于 bean 属性,
     * 不改 Map 的键),所以下面两个键名都要兜一下,免得换驱动/别名后静默拿到 null。
     */
    private void fillPostStats(List<CircleVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        List<Long> ids = vos.stream().map(CircleVO::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;

        Map<Long, int[]> counts = new java.util.HashMap<>();
        Map<Long, java.time.LocalDateTime> last = new java.util.HashMap<>();
        for (java.util.Map<String, Object> r : postMapper.postStatsByCircles(ids)) {
            Long cid = asLong(r.get("circle_id") != null ? r.get("circle_id") : r.get("circleId"));
            if (cid == null) continue;
            counts.put(cid, new int[]{asInt(r.get("post_cnt") != null ? r.get("post_cnt") : r.get("postCnt"))});
            last.put(cid, asTime(r.get("last_at") != null ? r.get("last_at") : r.get("lastAt")));
        }
        for (CircleVO vo : vos) {
            vo.setPostCount(counts.getOrDefault(vo.getId(), new int[]{0})[0]);
            java.time.LocalDateTime t = last.get(vo.getId());
            vo.setLastPostAt(t == null ? null : POST_TIME_FMT.format(t));
        }
    }

    private static final DateTimeFormatter POST_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static java.time.LocalDateTime asTime(Object o) {
        if (o instanceof java.time.LocalDateTime t) return t;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
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
        // 冗余计数:用户「加入的圈子数」+1(退圈时 -1),与 follower_count 同口径
        userMapper.adjustJoinedCircleCount(userId, 1);
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
        // 冗余计数:用户「加入的圈子数」-1(加圈时 +1),与 follower_count 同口径
        userMapper.adjustJoinedCircleCount(userId, -1);
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

        /*
         * 统一走 PostVoAssembler.toVoList(2026-10-26 修):
         * 此前这里手搓 VO 且写死 media(null) —— 圈子帖列表永远没有图片,
         * 点进详情才正常。toVoList 同时补齐话题、圈名、点赞/收藏态,
         * 作者脱敏与内容打码也走同一套规则,不再各写一份。
         */
        List<PostVO> vos = postVoAssembler.toVoList(page, viewerId);

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /**
     * 圈子名归一化:trim + 全角转半角(数字/字母)。
     * 全角「１」与半角「1」不应被视为两个名字,也不应让全角数字混进库。
     * 只转 ASCII 范围内有全角对应物的字符,汉字本身不变。
     */
    private String normalizeName(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if (c == ' ') continue; // 内部空格也去掉:「考研 圈」→「考研圈」,空格在列表里破坏对齐
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                sb.append((char) ('A' + (c - 'Ａ')));
            } else if (c >= 'ａ' && c <= 'ｚ') {
                sb.append((char) ('a' + (c - 'ａ')));
            } else if (c == '．') {
                sb.append('.');
            } else if (c == '－') {
                sb.append('-');
            } else if (c == '＿') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private CircleVO toVo(Circle c, long viewerId) {        UserVO ovo = UserVoAssembler.of(userMapper.selectById(c.getOwnerId()));

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
