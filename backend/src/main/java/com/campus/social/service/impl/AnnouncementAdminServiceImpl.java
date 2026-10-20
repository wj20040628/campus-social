package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.dto.AnnouncementCreateDTO;
import com.campus.social.dto.AnnouncementUpdateDTO;
import com.campus.social.entity.Announcement;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AnnouncementMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AnnouncementAdminService;
import com.campus.social.service.AnnouncementReachService;
import com.campus.social.service.AuditService;
import com.campus.social.service.support.AnnouncementAdminListAssembler;
import com.campus.social.vo.AdminAnnouncementPageVO;
import com.campus.social.vo.AnnouncementPublishVO;
import com.campus.social.vo.AnnouncementRemindVO;
import com.campus.social.vo.AnnouncementRow;
import com.campus.social.vo.UnreadUserPageVO;
import com.campus.social.websocket.WsPushService;
import com.campus.social.websocket.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.campus.social.common.ResultCode.ANNOUNCEMENT_NOT_FOUND;
import static com.campus.social.common.ResultCode.FORBIDDEN;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 公告管理端实现（F5，写入侧）。
 *
 * <p>「谁还没看」名单与在线提醒在 {@link AnnouncementReachService}：
 * 那边管「谁读了」，这里管「写公告」，两类逻辑的权限模型不同，
 * 放一起会让本类突破 300 行且难以看出边界。
 *
 * <p>发布推送只推在线用户：离线用户靠
 * {@code GET /announcement/unread-count} 的红点发现。
 * 给全校每人写一行 notify 会造成 1 万用户 × 100 公告 = 100 万行写入放大（SPEC §6.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementAdminServiceImpl implements AnnouncementAdminService {

    /** status */
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_UNPUBLISHED = 2;

    /** 列表摘要字数，与前台一致 */
    private static final int SUMMARY_LIMIT = 80;

    private final AnnouncementMapper announcementMapper;
    private final UserMapper userMapper;
    private final CampusProperties campus;
    private final WsPushService wsPush;
    private final WsSessionManager sessionManager;
    private final AnnouncementReachService reachService;
    private final AuditService auditService;

    @Override
    @Transactional
    public AnnouncementPublishVO create(long publisherId, AnnouncementCreateDTO dto) {
        requireVerified(publisherId);
        boolean publishNow = !Boolean.FALSE.equals(dto.getPublishNow());
        boolean top = Integer.valueOf(1).equals(dto.getIsTop());

        Announcement a = new Announcement();
        a.setTitle(dto.getTitle());
        a.setContent(dto.getContent());
        a.setCategory(dto.getCategory());
        a.setPublisherId(publisherId);
        a.setSchoolId(campus.getSchoolId());
        a.setIsTop(top ? 1 : 0);
        a.setTopExpireTime(top ? requireExpire(dto.getTopExpireTime()) : null);
        a.setStatus(publishNow ? STATUS_PUBLISHED : 0);
        a.setPublishTime(publishNow ? LocalDateTime.now() : null);
        a.setReadCount(0);
        announcementMapper.insert(a);

        int pushed = 0;
        if (publishNow) {
            // 只推在线用户：离线用户靠 GET /announcement/unread-count 的红点发现。
            // 给全校每人写一行 notify 会造成 1 万用户 × 100 公告 = 100 万行写入放大（SPEC §6.3）
            Set<Long> online = sessionManager.onlineUserIds();
            online.remove(publisherId);
            wsPush.pushAnnouncement(online, a.getId(), a.getTitle(), a.getCategory(), top);
            pushed = online.size();
        }
        log.info("announcement created: id={}, publisher={}, status={}, pushedOnline={}",
                a.getId(), publisherId, a.getStatus(), pushed);
        // 审计:新建公告(草稿/发布都记,便于追溯谁发了什么)
        auditService.record(publisherId, "announcement_create", "announcement", a.getId(),
                "title=" + a.getTitle() + ", status=" + (publishNow ? "已发布" : "草稿"), null);

        return AnnouncementPublishVO.builder()
                .id(a.getId())
                .status(a.getStatus())
                .isTop(a.getIsTop())
                .topExpireTime(a.getTopExpireTime())
                .pushedOnlineCount(pushed)
                .build();
    }

    @Override
    @Transactional
    public AnnouncementPublishVO update(long adminId, long announcementId, AnnouncementUpdateDTO dto) {
        // AC-11：编辑与发布同权。改完 title / content 并上架，对全校的效果与发布等价，
        // 只拦 create 不拦 update 等于留了一条绕行路径。
        // Controller 上已打了 @RequireVerified，这里是防绕过 Controller 直调 service 的兜底。
        requireVerified(adminId);
        Announcement a = announcementMapper.selectById(announcementId);
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已删除");
        }

        // 契约只允许 1上架 / 2下架，不允许打回草稿（0）：
        // 打回草稿会让它从所有人的前台消失却留着全部已读回执，管理员自己也找不回来。
        if (dto.getStatus() != null && dto.getStatus() != STATUS_PUBLISHED
                && dto.getStatus() != STATUS_UNPUBLISHED) {
            throw new BusinessException(UNPROCESSABLE, "状态只能是 1 上架或 2 下架");
        }

        if (dto.getTitle() != null) {
            a.setTitle(dto.getTitle());
        }
        if (dto.getContent() != null) {
            a.setContent(dto.getContent());
        }
        if (dto.getCategory() != null) {
            a.setCategory(dto.getCategory());
        }
        if (dto.getIsTop() != null) {
            boolean top = dto.getIsTop() == 1;
            a.setIsTop(top ? 1 : 0);
            a.setTopExpireTime(top ? requireExpire(dto.getTopExpireTime()) : null);
        } else if (dto.getTopExpireTime() != null && Integer.valueOf(1).equals(a.getIsTop())) {
            // 只改到期时间、不改 isTop 的情形
            a.setTopExpireTime(dto.getTopExpireTime());
        }

        boolean becomingPublished = dto.getStatus() != null
                && dto.getStatus() == STATUS_PUBLISHED
                && !Integer.valueOf(STATUS_PUBLISHED).equals(a.getStatus());
        if (becomingPublished) {
            // 首次上架才写 publish_time。重新上架保留原发布时间，
            // 否则列表按 publish_time 倒序会让「重新上架」变成「刚发布的」。
            if (a.getPublishTime() == null) {
                a.setPublishTime(LocalDateTime.now());
            }
            a.setStatus(STATUS_PUBLISHED);
        } else if (dto.getStatus() != null) {
            a.setStatus(dto.getStatus());
        }
        announcementMapper.updateById(a);

        int pushed = 0;
        if (becomingPublished) {
            Set<Long> online = sessionManager.onlineUserIds();
            online.remove(adminId);
            wsPush.pushAnnouncement(online, a.getId(), a.getTitle(), a.getCategory(),
                    Integer.valueOf(1).equals(a.getIsTop()));
            pushed = online.size();
        }
        log.info("announcement updated: id={}, admin={}, status={}, isTop={}",
                announcementId, adminId, a.getStatus(), a.getIsTop());
        // 审计:编辑公告(含上下架)
        auditService.record(adminId, "announcement_update", "announcement", a.getId(),
                "title=" + a.getTitle() + ", status=" + a.getStatus(), null);

        return AnnouncementPublishVO.builder()
                .id(a.getId())
                .status(a.getStatus())
                .isTop(a.getIsTop())
                .topExpireTime(a.getTopExpireTime())
                .pushedOnlineCount(pushed)
                .build();
    }

    @Override
    public AdminAnnouncementPageVO adminList(Long schoolId, Integer status, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<AnnouncementRow> rows = announcementMapper.selectAdminList(status, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AnnouncementRow> page = hasMore ? rows.subList(0, limit) : rows;

        // 触达率的分母对整页是同一个值，查一次即可；
        // 逐行查会把一次分页放大成 N 次 count。
        int totalTarget = Math.max(1, userMapper.countSchoolUsers(schoolId));

        AdminAnnouncementPageVO vo = new AdminAnnouncementPageVO();
        vo.setList(AnnouncementAdminListAssembler.toItems(page, totalTarget, SUMMARY_LIMIT));
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        return vo;
    }

    @Override
    public UnreadUserPageVO unreadUsers(long adminId, long announcementId, Long cursor, int size) {
        AnnouncementRow ann = requireReadable(announcementId);
        requirePublisherOrAdmin(adminId, ann);
        return reachService.unreadUsers(announcementId, ann.getSchoolId(), cursor, size);
    }

    @Override
    public AnnouncementRemindVO remind(long adminId, long announcementId) {
        AnnouncementRow ann = requireReadable(announcementId);
        requirePublisherOrAdmin(adminId, ann);
        AnnouncementRemindVO rv = reachService.remind(adminId, announcementId, ann.getSchoolId());
        // 审计:公告催读(向未读用户重新推送提醒)
        auditService.record(adminId, "announcement_remind", "announcement", announcementId,
                "公告催读 id=" + announcementId, null);
        return rv;
    }

    // ---------------------------------------------------------------- 私有

    /**
     * AC-11：未通过校内认证的用户不能发布 / 编辑公告。
     *
     * <p>放在 service 而非只靠前端隐藏按钮：按钮可以被绕过，
     * 真正的判定必须在服务端。
     *
     * <p><b>与 {@code @RequireVerified} 拦截器的分工</b>：拦截器层拦的是
     * 「HTTP 请求进来时」，本方法是「绕过 Controller 直调 service 时」的兜底。
     * 两层并存不冲突——重复校验只是多一次主键查询，
     * 换来「任一层失效，另一层仍然拦得住」。
     */
    private void requireVerified(long publisherId) {
        var publisher = userMapper.selectById(publisherId);
        if (publisher == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发布人不存在");
        }
        if (!Integer.valueOf(1).equals(publisher.getVerifyStatus())) {
            throw new BusinessException(ResultCode.USER_NOT_VERIFIED, "完成校内认证后才能发布公告");
        }
    }

    /** 置顶必须带到期时间。永不过期的置顶等于永久置顶，运营一旦忘改就再也下不来 */
    private LocalDateTime requireExpire(LocalDateTime topExpireTime) {
        if (topExpireTime == null) {
            throw new BusinessException(UNPROCESSABLE, "置顶必须设置到期时间");
        }
        if (!topExpireTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(UNPROCESSABLE, "置顶到期时间必须晚于当前时间");
        }
        return topExpireTime;
    }

    private AnnouncementRow requireReadable(long announcementId) {
        AnnouncementRow ann = announcementMapper.selectDetailWithPublisher(announcementId);
        if (ann == null) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已删除");
        }
        return ann;
    }

    /**
     * 只有发布方本人或管理员能看未读名单 / 发提醒。
     *
     * <p>未读名单等于「谁没看这条公告」的全校级画像，
     * 任意运营账号都能查会让它变成一个用户行为监控面。
     */
    private void requirePublisherOrAdmin(long adminId, AnnouncementRow ann) {
        if (adminId == ann.getPublisherId()) {
            return;
        }
        var admin = userMapper.selectById(adminId);
        if (admin == null || !Integer.valueOf(1).equals(admin.getRole())) {
            throw new BusinessException(FORBIDDEN, "只有该公告的发布方或管理员可以查看未读名单");
        }
    }
}