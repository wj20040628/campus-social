package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.dto.MktReactivDTO;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.MktActivityService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.util.MktRateLimiter;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktCommentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 校园限时活动集市实现。
 *
 * <p>2026-11-01 拆分：内容规则在 {@link MktActivityRules}，VO 装配在
 * {@link MktActivityVoAssembler}，留言在 {@link MktActivityCommentService}；
 * 本类保留发布/编辑/在投配额与属主校验。
 *
 * <p>时间统一东八区（服务器 TZ=Asia/Shanghai，见 docker-compose.yml），
 * 故 {@code expire_time > NOW()} 与 {@code LocalDateTime.now()} 口径一致。
 */
@Service
@RequiredArgsConstructor
public class MktActivityServiceImpl implements MktActivityService {

    private final MktActivityMapper mktActivityMapper;
    private final UserMapper userMapper;
    private final MessageContentGuard contentGuard;
    private final MktRateLimiter rateLimiter;
    /** 在投配额(2026-10-25):信誉分档 1/2/3,过期/暂停/删除释放名额 */
    private final ReviewTrustService trustService;
    /** 集市发布门槛：注册即可，但信誉分需 ≥60（2026-11-10） */
    private final CampusAccessService access;
    private final MktActivityCommentService commentService;
    private final MktActivityVoAssembler assembler;

    // ---------------------------------------------------------------- create

    @Override
    @Transactional
    public Long create(long userId, MktActivityDTO dto) {
        rateLimiter.guardPublish(userId);
        // 注册即可发布，但信誉分需 ≥60；不再校验账龄（2026-11-10）
        access.requireCredit(userId, "发布集市活动");
        // 在投配额:与频率限流互补(429 管太快,这里管太多)
        guardActiveQuota(userId, false, null, "发布");

        MktActivityRules.validateCategory(dto.getCategory());
        // 标题不再必填:主文案由「分类 · 游戏(游戏组队时)」派生,未传则留 null(前端不再采集)。
        String title = MktActivityRules.optionalTitle(dto.getTitle());
        String content = MktActivityRules.requireText(
                dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        LocalDateTime expire = MktActivityRules.validateExpire(dto.getExpireTime());

        /*
         * 敏感词(2026-10-24 改走统一横切):level-2 拒发,level-1 打码后入库。
         * 此前只拦拒发词,一级引流词(加微信/vx)在集市原样展示——
         * 集市是公开广场内容,与帖子同口径。标题与正文分开打码:
         * 拼接检查防不住跨字段的词,而 DFA 词内不含换行,跨字段拼接成词的场景可忽略。
         */
        String safeTitle = title == null ? null
                : contentGuard.sanitize(userId, "mkt", "mkt_activity", title);
        String safeContent = contentGuard.sanitize(userId, "mkt", "mkt_activity", content);

        MktActivity a = new MktActivity();
        a.setUserId(userId);
        a.setCategory(dto.getCategory().trim());
        a.setSubTag(MktActivityRules.blankToNull(dto.getSubTag()));
        // 数据库 title 列 NOT NULL:未传标题时落「分类 · 游戏」派生主文案,兼容旧读法。
        String storedTitle = MktActivityRules.blankToNull(safeTitle);
        a.setTitle(storedTitle != null ? storedTitle
                : MktActivityRules.derivedTitle(dto.getCategory(), dto.getSubTag()));
        a.setContent(safeContent);
        a.setTargetNum(dto.getTargetNum());
        a.setPrice(dto.getPrice());
        a.setLocation(MktActivityRules.blankToNull(dto.getLocation()));
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
            MktActivityRules.validateCategory(category);
        }
        String cat = (category == null || category.isBlank()) ? null : category.trim();
        List<MktActivity> rows = mktActivityMapper.selectPlaza(cat, lastId, size + 1);
        return assembler.toPlazaPage(rows, size);
    }

    @Override
    public CursorPage<MktActivityVO> myList(long userId, Long lastId, int size) {
        List<MktActivity> rows = mktActivityMapper.selectByUser(userId, lastId, size + 1);
        boolean hasMore = rows.size() > size;
        List<MktActivity> page = rows.stream().limit(size).toList();
        Map<Long, User> users = assembler.usersByIds(
                page.stream().map(MktActivity::getUserId).toList());

        List<MktActivityVO> vos = page.stream().map(a -> {
            MktActivityVO vo = assembler.toVo(a);
            vo.setStatus(assembler.statusOf(a));
            assembler.fillPublisher(vo, users.get(a.getUserId()));
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
        MktActivityVO vo = assembler.toVo(a);
        vo.setStatus(assembler.statusOf(a));
        assembler.fillPublisher(vo, userMapper.selectById(a.getUserId()));
        return vo;
    }

    // ---------------------------------------------------------------- edits
    // 属主操作（edit/enable/full/reactivate/delete）：controller 把 me 传进来，
    // 这里用 ownedBy 强制 user_id==me，非属主 403、软删/不存在 404。

    @Override
    @Transactional
    public void edit(long id, long me, MktActivityDTO dto) {
        MktActivity a = ownedBy(id, me);
        MktActivityRules.validateCategory(dto.getCategory());
        // 标题可选:留空则置 null(前端不再采集,主文案走「分类 · 游戏」)。
        String title = MktActivityRules.optionalTitle(dto.getTitle());
        String content = MktActivityRules.requireText(
                dto.getContent(), ResultCode.MKT_CONTENT_INVALID, 1, 500);
        // 2026-10-24 与 create 同口径:level-2 拒发,level-1 打码后入库
        String safeTitle = title == null ? null
                : contentGuard.sanitize(me, "mkt", "mkt_activity", title);
        String safeContent = contentGuard.sanitize(me, "mkt", "mkt_activity", content);

        a.setCategory(dto.getCategory().trim());
        a.setSubTag(MktActivityRules.blankToNull(dto.getSubTag()));
        String storedTitle = MktActivityRules.blankToNull(safeTitle);
        a.setTitle(storedTitle != null ? storedTitle
                : MktActivityRules.derivedTitle(dto.getCategory(), dto.getSubTag()));
        a.setContent(safeContent);
        a.setTargetNum(dto.getTargetNum());
        a.setPrice(dto.getPrice());
        a.setLocation(MktActivityRules.blankToNull(dto.getLocation()));
        // 编辑只改文案：不改 expire_time、不重置 is_enable / is_full / last_launch_time
        mktActivityMapper.updateById(a);
    }

    @Override
    @Transactional
    public void toggleEnable(long id, long me, boolean enable) {
        MktActivity a = ownedBy(id, me);
        /*
         * 在投配额(2026-10-25 补洞):开启一条「暂停但未过期」的活动会把它拉回
         * 在投池,与重激活是同一个绕过口,必须同检。注意两个不拦的边界:
         * 已在投的开=无操作;**已过期**的开不改变在投数(expire_time 仍在过去,
         * 拉不进广场),想回广场必须走 reactivate(那里刷新到期并查配额)。
         */
        if (enable && !isActivelyLive(a)
                && a.getExpireTime() != null && a.getExpireTime().isAfter(LocalDateTime.now())) {
            guardActiveQuota(me, false, a, "重新开启");
        }
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
        LocalDateTime expire = MktActivityRules.validateExpire(dto.getNewExpire());
        rateLimiter.guardReactivate(id);
        /*
         * 在投配额(2026-10-25 补洞):重激活把过期/暂停的活动拉回「在投」池。
         * 只在新发布查配额的话,「发 A → 到期 → 发 B → 重激活 A」可以无限堆在投数。
         * 已在投的活动走重激活=续期,不增加在投数,不占新名额(仍受 24h/3 次限流)。
         */
        guardActiveQuota(me, true, a, "再激活");
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
        commentService.addComment(activityId, userId, dto);
    }

    @Override
    public CursorPage<MktCommentVO> listComments(long activityId, Long lastId, int size) {
        return commentService.listComments(activityId, lastId, size);
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

    /** 是否处于在投状态(投放中且未过期);软删行过不了 ownedBy 的 selectById */
    private boolean isActivelyLive(MktActivity a) {
        return Integer.valueOf(1).equals(a.getIsEnable())
                && a.getExpireTime() != null && a.getExpireTime().isAfter(LocalDateTime.now());
    }

    /**
     * 在投配额守护:create / reactivate / toggleEnable(开启) 三个「使活动进入
     * 在投池」的入口共用——漏一个口子,配额就能被「暂停→开启」「到期→重激活」绕过。
     *
     * @param skipIfAlreadyLive true 时,目标已在投则直接放行(重激活=续期,
     *                          不增加在投数,不该被拦);create 恒传 false
     * @param target            被操作的活动;create 无目标传 null
     * @param action            报错文案里的动作词(发布/再激活/重新开启)
     */
    private void guardActiveQuota(long userId, boolean skipIfAlreadyLive, MktActivity target, String action) {
        if (skipIfAlreadyLive && target != null && isActivelyLive(target)) {
            return;
        }
        /*
         * 并发发布时「先 count 再 insert」会同时查到 0，一起越过配额。
         * 锁住 user 行把这段读-算-写串行化；与文件容量配额同一做法。
         * 在投列表必须用锁定读（FOR UPDATE）：普通快照读可能看不到
         * 等锁期间刚提交的插入，锁定读始终读取最新已提交数据。
         * 必须在事务内调用。
         */
        userMapper.lockById(userId);
        int quota = trustService.creationQuota(userId);
        if (quota <= 0 || mktActivityMapper.lockActiveIdsByUser(userId).size() >= quota) {
            throw new BusinessException(ResultCode.MKT_ACTIVE_LIMIT_REACHED,
                    "在投的集市活动已达上限(" + quota + " 条),等待到期或暂停/删除腾出名额,提升信誉分后自动扩容");
        }
    }
}
