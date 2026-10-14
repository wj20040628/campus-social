package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.dto.MktReactivDTO;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.MktComment;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.MktCommentMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.MktActivityService;
import com.campus.social.util.MktRateLimiter;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktCommentVO;
import com.campus.social.vo.MktStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 校园限时活动集市实现。
 *
 * <p>时间统一东八区（服务器 TZ=Asia/Shanghai，见 docker-compose.yml），
 * 故 {@code expire_time > NOW()} 与 {@code LocalDateTime.now()} 口径一致。
 */
@Service
@RequiredArgsConstructor
public class MktActivityServiceImpl implements MktActivityService {

    /** 一级分类白名单（9 选 1）。运营维护，与前端发布页下拉一致。 */
    private static final Set<String> CATEGORIES = Set.of(
            "游戏组队", "跑腿代办", "拼车出行", "旅游结伴", "学习搭子",
            "运动约伴", "干饭搭子", "闲置互助", "其它");

    /** 单次投放时长上限。 */
    private static final int MAX_LAUNCH_HOURS = 12;

    /** 站内通知类型：有人在我的集市活动下留言 → 通知发布者。
     * 接在既有 notify type 序列（1 被关注…9 被@）之后，见 NotifyServiceImpl.summaryFor。 */
    private static final int MKT_COMMENT_NOTIFY_TYPE = 10;

    private final MktActivityMapper mktActivityMapper;
    private final MktCommentMapper mktCommentMapper;
    private final UserMapper userMapper;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final MktRateLimiter rateLimiter;
    private final PostNotifySupport postNotifySupport;

    // ---------------------------------------------------------------- create

    @Override
    @Transactional
    public Long create(long userId, MktActivityDTO dto) {
        rateLimiter.guardPublish(userId);

        validateCategory(dto.getCategory());
        String title = requireText(dto.getTitle(), ResultCode.MKT_TITLE_INVALID, 15, 25);
        String content = requireText(dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        LocalDateTime expire = validateExpire(dto.getExpireTime());

        blockIfSensitive(title + "\n" + content);

        MktActivity a = new MktActivity();
        a.setUserId(userId);
        a.setCategory(dto.getCategory().trim());
        a.setSubTag(blankToNull(dto.getSubTag()));
        a.setTitle(title);
        a.setContent(content);
        a.setTargetNum(dto.getTargetNum());
        a.setPrice(dto.getPrice());
        a.setLocation(blankToNull(dto.getLocation()));
        a.setIsEnable(1);
        a.setExpireTime(expire);
        a.setIsFull(0);
        a.setLastLaunchTime(LocalDateTime.now());
        // createTime 由 MyMetaObjectHandler 自动填充
        mktActivityMapper.insert(a);
        return a.getId();
    }

    // ---------------------------------------------------------------- plaza / my

    @Override
    public CursorPage<MktActivityVO> plaza(String category, Long lastId, int size) {
        if (category != null && !category.isBlank()) {
            validateCategory(category);
        }
        String cat = (category == null || category.isBlank()) ? null : category.trim();
        List<MktActivity> rows = mktActivityMapper.selectPlaza(cat, lastId, size + 1);
        return toPlazaPage(rows, size);
    }

    @Override
    public CursorPage<MktActivityVO> myList(long userId, Long lastId, int size) {
        List<MktActivity> rows = mktActivityMapper.selectByUser(userId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<MktActivity> page = rows.stream().limit(size).toList();
        Map<Long, User> users = usersByIds(page.stream().map(MktActivity::getUserId).toList());

        List<MktActivityVO> vos = page.stream().map(a -> {
            MktActivityVO vo = toVoNoStatus(a);
            vo.setStatus(deriveStatus(a));
            fillPublisher(vo, users.get(a.getUserId()));
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public MktActivityVO detail(long id, long viewerId) {
        MktActivity a = mktActivityMapper.selectById(id);
        if (a == null) {
            // 软删（isDeleted=1）或不存在 → 一律 404
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
        MktActivityVO vo = toVoNoStatus(a);
        vo.setStatus(deriveStatus(a));
        fillPublisher(vo, userMapper.selectById(a.getUserId()));
        return vo;
    }

    // ---------------------------------------------------------------- edits
    // 属主操作（edit/enable/full/reactivate/delete）：controller 把 me 传进来，
    // 这里用 ownedBy 强制 user_id==me，非属主 403、软删/不存在 404。

    @Override
    @Transactional
    public void edit(long id, long me, MktActivityDTO dto) {
        MktActivity a = ownedBy(id, me);
        validateCategory(dto.getCategory());
        String title = requireText(dto.getTitle(), ResultCode.MKT_TITLE_INVALID, 15, 25);
        String content = requireText(dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        blockIfSensitive(title + "\n" + content);

        a.setCategory(dto.getCategory().trim());
        a.setSubTag(blankToNull(dto.getSubTag()));
        a.setTitle(title);
        a.setContent(content);
        a.setTargetNum(dto.getTargetNum());
        a.setPrice(dto.getPrice());
        a.setLocation(blankToNull(dto.getLocation()));
        // 编辑只改文案：不改 expire_time、不重置 is_enable / is_full / last_launch_time
        mktActivityMapper.updateById(a);
    }

    @Override
    @Transactional
    public void toggleEnable(long id, long me, boolean enable) {
        ownedBy(id, me);
        int affected = mktActivityMapper.updateEnable(id, me, enable ? 1 : 0);
        if (affected == 0) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void markFull(long id, long me, boolean full) {
        ownedBy(id, me);
        int affected = mktActivityMapper.updateFull(id, me, full ? 1 : 0);
        if (affected == 0) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void reactivate(long id, long me, MktReactivDTO dto) {
        MktActivity a = ownedBy(id, me);
        LocalDateTime expire = validateExpire(dto.getNewExpire());
        rateLimiter.guardReactivate(id);
        int affected = mktActivityMapper.reactivate(id, me, expire, LocalDateTime.now());
        if (affected == 0) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void delete(long id, long me) {
        ownedBy(id, me);
        mktActivityMapper.softDelete(id, me);
    }

    // ---------------------------------------------------------------- comments

    @Override
    @Transactional
    public void addComment(long activityId, long userId, MktCommentDTO dto) {
        MktActivity a = mktActivityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
        rateLimiter.guardComment(userId);
        String content = requireText(dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        blockIfSensitive(content);

        MktComment c = new MktComment();
        c.setActivityId(activityId);
        c.setUserId(userId);
        c.setContent(content);
        mktCommentMapper.insert(c);

        // 站内通知（增量①）：有人在我的活动下留言 → 通知发布者。
        // 复用 PostNotifySupport：写 notify + bump unread + WS 推。
        // 不通知自己（给自己留言无意义）；活动本身不在审核队列，故不涉及 type 6/7/8。
        if (!Objects.equals(a.getUserId(), userId)) {
            postNotifySupport.pushNotify(a.getUserId(), MKT_COMMENT_NOTIFY_TYPE, activityId, userId, null);
        }
    }

    @Override
    public CursorPage<MktCommentVO> listComments(long activityId, Long lastId, int size) {
        MktActivity a = mktActivityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
        List<MktComment> rows = mktCommentMapper.selectByActivity(activityId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<MktComment> page = rows.stream().limit(size).toList();
        Map<Long, User> users = usersByIds(page.stream().map(MktComment::getUserId).toList());

        List<MktCommentVO> vos = page.stream().map(c -> {
            MktCommentVO vo = MktCommentVO.builder()
                    .id(c.getId())
                    .activityId(c.getActivityId())
                    .userId(c.getUserId())
                    .content(c.getContent())
                    .createTime(c.getCreateTime())
                    .build();
            fillCommentUser(vo, users.get(c.getUserId()));
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    // ---------------------------------------------------------------- helpers

    /** 属主校验：取活动（软删/不存在 → 404）；非属主 → 403。 */
    private MktActivity ownedBy(long id, long me) {
        MktActivity a = mktActivityMapper.selectById(id);
        if (a == null) {
            throw new BusinessException(ResultCode.MKT_NOT_FOUND);
        }
        if (!Objects.equals(a.getUserId(), me)) {
            throw new BusinessException(ResultCode.MKT_NOT_OWNER);
        }
        return a;
    }

    private void validateCategory(String category) {
        if (category == null || !CATEGORIES.contains(category.trim())) {
            throw new BusinessException(ResultCode.MKT_CATEGORY_INVALID);
        }
    }

    /** 空串归一为 null（sub_tag / location 等可选字段）。 */
    private String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 取非空文本并做 15–25 / 1–500 字符长度硬校验。
     * 中英文各计 1 字符（Java {@code String.length} 即 UTF-16 码元数，对常见中文/ASCII 等价）。
     */
    private String requireText(String raw, ResultCode rc, int min, int max) {
        if (raw == null) {
            throw new BusinessException(rc);
        }
        String t = raw.trim();
        if (t.length() < min || t.length() > max) {
            throw new BusinessException(rc);
        }
        return t;
    }

    /**
     * 投放截止时间校验：now < expire <= now + MAX_LAUNCH_HOURS。
     * 既拦「过去时间」也拦「单次超 12h」——防前端篡改。
     */
    private LocalDateTime validateExpire(LocalDateTime expire) {
        if (expire == null) {
            throw new BusinessException(ResultCode.MKT_EXPIRE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!expire.isAfter(now) || Duration.between(now, expire).toHours() >= MAX_LAUNCH_HOURS) {
            throw new BusinessException(ResultCode.MKT_EXPIRE_INVALID);
        }
        return expire;
    }

    /** 敏感词 level=2 命中 → 拒收。 */
    private void blockIfSensitive(String text) {
        if (sensitiveWordFilter.containsBlock(text)) {
            throw new BusinessException(ResultCode.SENSITIVE_WORD_BLOCKED,
                    "内容包含违规词，已拒绝提交");
        }
    }

    /** 软删由 MyBatis-Plus 的 {@code selectById}（@TableLogic）自动过滤；返回 null 即 404。 */
    private MktStatus deriveStatus(MktActivity a) {
        boolean isFull = Integer.valueOf(1).equals(a.getIsFull());
        boolean isEnable = Integer.valueOf(1).equals(a.getIsEnable());
        boolean expired = a.getExpireTime() == null || !a.getExpireTime().isAfter(LocalDateTime.now());
        return MktStatus.derive(isFull, isEnable, expired);
    }

    private MktActivityVO toVoNoStatus(MktActivity a) {
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
                .build();
    }

    /** 广场/详情行：填发布者昵称/头像（脱敏由前端 displayName 兜底，此处取原昵称）。 */
    private void fillPublisher(MktActivityVO vo, User u) {
        if (vo == null || u == null) {
            return;
        }
        vo.setNickname(u.getNickname());
        vo.setAvatarUrl(u.getAvatarUrl());
    }

    private void fillCommentUser(MktCommentVO vo, User u) {
        if (vo == null || u == null) {
            return;
        }
        vo.setNickname(u.getNickname());
        vo.setAvatarUrl(u.getAvatarUrl());
    }

    /** 批量取用户消 N+1；广场分页最多 100 条，一次性取。 */
    private Map<Long, User> usersByIds(List<Long> ids) {
        Set<Long> uniq = new HashSet<>(ids);
        uniq.remove(null);
        if (uniq.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectByIds(uniq);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /**
     * 把一页广场实体转成带发布者的 VO 列表 + 游标。
     * 不填 status（广场行不显示 4 态，那是「我的发布」的事）。
     */
    private CursorPage<MktActivityVO> toPlazaPage(List<MktActivity> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<MktActivity> page = rows.stream().limit(size).toList();
        Map<Long, User> users = usersByIds(page.stream().map(MktActivity::getUserId).toList());

        List<MktActivityVO> vos = page.stream().map(a -> {
            MktActivityVO vo = toVoNoStatus(a);
            fillPublisher(vo, users.get(a.getUserId()));
            return vo;
        }).toList();

        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }
}
