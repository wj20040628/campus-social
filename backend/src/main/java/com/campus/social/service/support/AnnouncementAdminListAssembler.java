package com.campus.social.service.support;

import com.campus.social.vo.AnnouncementAdminItemVO;
import com.campus.social.vo.AnnouncementPublisherVO;
import com.campus.social.vo.AnnouncementRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台公告列表的行 → VO 转换。
 *
 * <p>从 {@code AnnouncementAdminServiceImpl} 拆出来的原因不是「行数超标」，
 * 而是一句话就能说清边界：<b>这里只做形状转换，不碰数据库、不做权限判定</b>。
 * 触达率的分母由调用方一次查好传进来——它是整页共用的值，
 * 若下沉到本类自己查，等于把「整页一次 count」退化成「每行一次 count」。
 *
 * <p>列表摘要字数与前台 {@code AnnouncementServiceImpl} 保持一致，
 * 同一篇公告在前后台看到的前 80 字必须相同，否则管理员会以为前台截错了。
 */
public final class AnnouncementAdminListAssembler {

    /** 列表摘要字数（openapi AnnouncementListItem.summary） */
    private static final int SUMMARY_LIMIT = 80;

    /** announcement.status */
    private static final int STATUS_PUBLISHED = 1;

    private AnnouncementAdminListAssembler() {
    }

    /**
     * @param rows        当前页数据（调用方已截断到 size 条）
     * @param totalTarget 触达率分母：同校用户数。整页共用一个值
     * @param summaryLimit 摘要字数上限
     */
    public static List<AnnouncementAdminItemVO> toItems(List<AnnouncementRow> rows,
            int totalTarget, int summaryLimit) {
        List<AnnouncementAdminItemVO> list = new ArrayList<>(rows.size());
        for (AnnouncementRow row : rows) {
            list.add(toItem(row, totalTarget, summaryLimit));
        }
        return list;
    }

    private static AnnouncementAdminItemVO toItem(AnnouncementRow row, int totalTarget, int summaryLimit) {
        int readCount = row.getReadCount() == null ? 0 : row.getReadCount();
        return AnnouncementAdminItemVO.builder()
                .id(row.getId())
                .title(row.getTitle())
                .summary(summarize(row.getContent(), summaryLimit))
                .category(row.getCategory())
                .isTop(row.getIsTop())
                .topExpireTime(row.getTopExpireTime())
                .status(row.getStatus())
                .publishTime(row.getPublishTime())
                .readCount(readCount)
                // 后台列表不判 isRead（列表不是相对某个用户的），固定 false
                .isRead(false)
                .publisher(toPublisher(row))
                .totalTargetCount(totalTarget)
                // 草稿没发布过，触达率恒为 0；不展示 0/1 这种会误导的数字
                .reachRate(Integer.valueOf(STATUS_PUBLISHED).equals(row.getStatus())
                        ? Math.min(1.0, (double) readCount / totalTarget) : 0.0)
                .build();
    }

    private static AnnouncementPublisherVO toPublisher(AnnouncementRow row) {
        return AnnouncementPublisherVO.builder()
                .id(row.getPublisherId())
                .nickname(row.getPublisherNickname())
                .avatarUrl(row.getPublisherAvatar())
                .verified(Integer.valueOf(1).equals(row.getPublisherVerify()))
                .build();
    }

    private static String summarize(String content, int limit) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}