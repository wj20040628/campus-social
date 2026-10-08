package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.AnnouncementRead;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.AnnouncementMapper;
import com.campus.social.mapper.AnnouncementReadMapper;
import com.campus.social.service.AnnouncementService;
import com.campus.social.vo.AnnouncementDetailVO;
import com.campus.social.vo.AnnouncementListItemVO;
import com.campus.social.vo.AnnouncementPublisherVO;
import com.campus.social.vo.AnnouncementReadVO;
import com.campus.social.vo.AnnouncementRow;
import com.campus.social.vo.UnreadCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.campus.social.common.ResultCode.ANNOUNCEMENT_NOT_FOUND;

/**
 * 公告前台实现（F5）。
 *
 * <p>可见性是本类唯一需要反复确认的东西：列表只看已发布同校；
 * 详情对已下架/草稿放行「发布方本人」——管理员要能预览自己没发的草稿，
 * 但别人一律404（而不是 403，403 等于确认「这条草稿存在」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    /** 列表摘要字数（openapi AnnouncementListItem.summary） */
    private static final int SUMMARY_LIMIT = 80;

    /** 置顶公告的取回上限。置顶是「少量」产品语义，这个值防的是脏数据 */
    private static final int PINNED_FETCH_LIMIT = 20;

    private final AnnouncementMapper announcementMapper;
    private final AnnouncementReadMapper readMapper;
    private final CampusProperties campus;

    @Override
    public CursorPage<AnnouncementListItemVO> frontList(Long schoolId, Long userId,
            Integer category, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<AnnouncementRow> rows = new ArrayList<>();

        // 置顶流：只在第一页取。翻页时置顶项已在第一页出现过，重复展示会错位。
        if (cursor == null) {
            List<AnnouncementRow> pinned = announcementMapper.selectPinnedFront(schoolId, PINNED_FETCH_LIMIT);
            rows.addAll(pinned);
            int remain = limit - pinned.size();
            if (remain > 0) {
                rows.addAll(announcementMapper.selectNormalFrontFirst(schoolId, category, remain + 1));
            }
        } else {
            rows.addAll(announcementMapper.selectNormalFrontAfter(schoolId, category, cursor, limit + 1));
        }

        boolean hasMore = rows.size() > limit;
        List<AnnouncementRow> page = hasMore ? rows.subList(0, limit) : rows;

        Set<Long> readIds = readIdsOf(userId, page);
        List<AnnouncementListItemVO> list = new ArrayList<>(page.size());
        for (AnnouncementRow row : page) {
            list.add(toListItem(row, readIds.contains(row.getId())));
        }

        // 游标取本页最后一条的 id。置顶流不参与游标推进：
        // 若把置顶的 id 写进 nextCursor，第 2 页的 id < cursor 会漏掉正常公告。
        Long nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = page.get(page.size() - 1).getId();
            // 本页全是置顶（脏数据导致普通流为空）时不能拿置顶 id 当游标
            if (nextCursor != null && allPinned(page)) {
                nextCursor = null;
            }
        }
        return new CursorPage<>(list, nextCursor, hasMore);
    }

    @Override
    public AnnouncementDetailVO detail(Long userId, Long announcementId, Long schoolId) {
        AnnouncementRow row = announcementMapper.selectDetailWithPublisher(announcementId);
        if (row == null) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已删除");
        }
        boolean isPublisher = userId != null && userId.equals(row.getPublisherId());
        // 未发布的（草稿/已下架）只有发布方本人能看。
        // 回 404 而非 403：403 会确认「这条草稿确实存在」，等于泄露运营动向。
        if (!Integer.valueOf(1).equals(row.getStatus()) && !isPublisher) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已下架");
        }
        if (!isPublisher && !row.getSchoolId().equals(schoolId)) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已下架");
        }

        AnnouncementReadVO read = null;
        AnnouncementRead mine = readMapper.selectOne(announcementId, userId);
        if (mine != null) {
            read = AnnouncementReadVO.builder()
                    .announcementId(announcementId)
                    .readTime(mine.getReadTime())
                    .duplicated(true)
                    .build();
        }

        return AnnouncementDetailVO.builder()
                .id(row.getId())
                .title(row.getTitle())
                .content(row.getContent())
                .category(row.getCategory())
                .isTop(effectiveIsTop(row))
                .topExpireTime(row.getTopExpireTime())
                .status(row.getStatus())
                .publishTime(row.getPublishTime())
                .readCount(row.getReadCount())
                .isRead(mine != null)
                .readTime(read == null ? null : read.getReadTime())
                .publisher(toPublisher(row))
                // AC-10：只有「自己是发布方 且 已认证」才给未读名单入口
                .canViewUnreadList(isPublisher && Integer.valueOf(1).equals(row.getPublisherVerify()))
                .build();
    }

    @Override
    @Transactional
    public AnnouncementReadVO markRead(Long userId, Long announcementId, Long schoolId) {
        AnnouncementRow row = announcementMapper.selectDetailWithPublisher(announcementId);
        if (row == null || !Integer.valueOf(1).equals(row.getStatus()) || !row.getSchoolId().equals(schoolId)) {
            throw new BusinessException(ANNOUNCEMENT_NOT_FOUND, "公告不存在或已下架");
        }

        // 幂等三件套（AC-09）：先查 → 插一行（撞唯一键即重复）→ 只有真插入才 +1。
        //
        // 这里刻意用「捕获 DuplicateKeyException」而不是「看 insert 的返回值」：
        // mysql-connector-j 默认携带 CLIENT_FOUND_ROWS，
        // ON DUPLICATE KEY UPDATE 的返回值区分不出「新插入」与「冲突赋回原值」，
        // 靠返回值判断会让重复提交把 read_count 反复 +1，触达率虚高到超过 100%。
        AnnouncementRead existing = readMapper.selectOne(announcementId, userId);
        if (existing != null) {
            return readResult(announcementId, existing.getReadTime(), true);
        }

        try {
            readMapper.insertRead(announcementId, userId);
        } catch (DuplicateKeyException e) {
            // 并发窗口内另一个请求刚插完，唯一键挡住了重复行。
            // MySQL 只回滚这条失败语句、不回滚整个事务，所以这里继续查是安全的。
            // 按「已读」返回而不是报错：用户的意图（我读了）已经达成，
            // 为此回 500 只会让他以为自己没点上，刷新后又变成一条新记录。
            AnnouncementRead mine = readMapper.selectOne(announcementId, userId);
            return readResult(announcementId, mine == null ? null : mine.getReadTime(), true);
        }

        // 只有真正新增回执时才 +1，否则触达率会高于 100%
        announcementMapper.incrReadCount(announcementId);
        AnnouncementRead created = readMapper.selectOne(announcementId, userId);
        log.debug("announcement read: ann={}, user={}", announcementId, userId);
        return readResult(announcementId, created == null ? null : created.getReadTime(), false);
    }

    private AnnouncementReadVO readResult(Long announcementId, java.time.LocalDateTime readTime,
            boolean duplicated) {
        return AnnouncementReadVO.builder()
                .announcementId(announcementId)
                .readTime(readTime)
                .duplicated(duplicated)
                .build();
    }

    @Override
    public UnreadCountVO unreadCount(Long userId, Long schoolId) {
        int published = readMapper.countPublishedInSchool(schoolId);
        int read = readMapper.countReadInSchool(userId, schoolId);
        int unread = Math.max(0, published - read);
        return UnreadCountVO.builder()
                .unreadCount(unread)
                .latestAnnouncementId(unread == 0 ? null : readMapper.selectLatestUnreadId(userId, schoolId))
                .build();
    }

    // ---------------------------------------------------------------- 私有

    /** 一页内我已读的公告 id 集合（一次 IN 查询，避免逐条判isRead 造成 N+1） */
    private Set<Long> readIdsOf(Long userId, List<AnnouncementRow> page) {
        if (userId == null || page.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> ids = new ArrayList<>(page.size());
        for (AnnouncementRow row : page) {
            ids.add(row.getId());
        }
        return new HashSet<>(readMapper.selectReadIds(userId, ids));
    }

    private boolean allPinned(List<AnnouncementRow> page) {
        for (AnnouncementRow row : page) {
            if (!isPinned(row)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPinned(AnnouncementRow row) {
        return Integer.valueOf(1).equals(row.getIsTop())
                && row.getTopExpireTime() != null
                && row.getTopExpireTime().isAfter(java.time.LocalDateTime.now());
    }

    /**
     * 置顶是否「当前生效」。
     *
     * <p>is_top=1 但 top_expire_time 已过时，置顶已经失效。
     * 前台查询已用 {@code top_expire_time > NOW()} 过滤，
     * 但详情是按 id 取的单行，不过滤——这里补上，否则会看到
     * 「列表里排在中间的公告，详情页却显示置顶」。
     */
    private Integer effectiveIsTop(AnnouncementRow row) {
        return isPinned(row) ? 1 : 0;
    }

    private AnnouncementListItemVO toListItem(AnnouncementRow row, boolean isRead) {
        return AnnouncementListItemVO.builder()
                .id(row.getId())
                .title(row.getTitle())
                .summary(summarize(row.getContent()))
                .category(row.getCategory())
                .isTop(effectiveIsTop(row))
                .topExpireTime(row.getTopExpireTime())
                .status(row.getStatus())
                .publishTime(row.getPublishTime())
                .readCount(row.getReadCount())
                .isRead(isRead)
                .publisher(toPublisher(row))
                .build();
    }

    private String summarize(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= SUMMARY_LIMIT ? flat : flat.substring(0, SUMMARY_LIMIT) + "…";
    }

    private AnnouncementPublisherVO toPublisher(AnnouncementRow row) {
        boolean verified = Integer.valueOf(1).equals(row.getPublisherVerify());
        return AnnouncementPublisherVO.builder()
                .id(row.getPublisherId())
                .nickname(row.getPublisherNickname())
                .avatarUrl(row.getPublisherAvatar())
                .verified(verified)
                // AC-12：未认证不挂学校名，避免运营号冒充校方权威
                .schoolName(verified ? campus.getSchoolName() : null)
                .build();
    }
}