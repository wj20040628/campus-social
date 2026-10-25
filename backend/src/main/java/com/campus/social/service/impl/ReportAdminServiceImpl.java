package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.service.ReportAdminService;
import com.campus.social.vo.ReportAdminPageVO;
import com.campus.social.vo.ReportListVO;
import com.campus.social.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 举报管理端查询实现（F4，AC-14 手机端可用）。
 *
 * <p>两处为「避免手机端额外请求」而做的取舍：
 * <ul>
 *   <li>被举报内容摘要按 targetType 分派批量取，不让管理员点开详情再等一轮</li>
 *   <li>reasonText 超过 60 字截断——OD-19 要求 375px 视口可用，
 *       一段 200 字的长文会把卡片撑到需要横向滚动才能读完</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAdminServiceImpl implements ReportAdminService {

    /** 补充说明在列表中的最大字数 */
    private static final int REASON_TEXT_LIMIT = 60;

    /** 被举报内容摘要在列表中的最大字数 */
    private static final int TARGET_SUMMARY_LIMIT = 80;

    private final ReportMapper reportMapper;
    private final com.campus.social.mapper.UserMapper userMapper;

    @Override
    public ReportAdminPageVO list(Integer status, Boolean handled, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<ReportListVO> rows = reportMapper.selectAdminList(status, handled, cursor, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<ReportListVO> page = hasMore ? rows.subList(0, limit) : rows;

        // 作者昵称按页批量回填:先逐行解析 authorId(与 summaryOf 同一 N+1 取舍),
        // 昵称一次 IN 查回来,不让一页 50 条变成 50 次 user 单查
        java.util.Map<Long, String> nickByAuthor = nicknameBatchOf(page);

        List<ReportVO> list = new ArrayList<>(page.size());
        for (ReportListVO row : page) {
            list.add(toVo(row, nickByAuthor));
        }

        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        ReportAdminPageVO vo = new ReportAdminPageVO();
        vo.setList(list);
        vo.setNextCursor(nextCursor);
        vo.setHasMore(hasMore);
        // 全量待审数而非本页统计：手机端顶栏红点要的是「还剩多少待办」，
        // 翻到第 3 页时红点显示 0 会让管理员以为活干完了
        vo.setPendingCount(reportMapper.countPending());
        return vo;
    }

    /**
     * 解析每行的被举报作者昵称(2026-10-24 加)。
     * targetType=1(举报用户)的"作者"就是被举报人本身。
     */
    private java.util.Map<Long, String> nicknameBatchOf(List<ReportListVO> page) {
        java.util.Set<Long> authorIds = new java.util.HashSet<>();
        for (ReportListVO row : page) {
            Long id = authorIdOf(row);
            if (id != null) {
                authorIds.add(id);
            }
        }
        if (authorIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (com.campus.social.entity.User u : userMapper.selectBatchIds(authorIds)) {
            out.put(u.getId(), u.getNickname());
        }
        return out;
    }

    /** 按 targetType 解析被举报作者 id;查不到(内容已删)返回 null */
    private Long authorIdOf(ReportListVO row) {
        if (row.getTargetType() == null || row.getTargetId() == null) {
            return null;
        }
        Long id = row.getTargetId();
        return switch (row.getTargetType()) {
            case ReportTargetType.POST -> reportMapper.selectPostAuthorId(id);
            case ReportTargetType.COMMENT -> reportMapper.selectCommentAuthorId(id);
            case ReportTargetType.CHAT_MESSAGE -> reportMapper.selectChatMessageSenderId(id);
            case ReportTargetType.USER -> id;
            case ReportTargetType.MKT_ACTIVITY -> reportMapper.selectMktActivityOwnerId(id);
            default -> null;
        };
    }

    private ReportVO toVo(ReportListVO row, java.util.Map<Long, String> nickByAuthor) {
        boolean truncated = false;
        String reasonText = truncate(row.getReasonText(), REASON_TEXT_LIMIT, false);
        if (row.getReasonText() != null && row.getReasonText().length() > REASON_TEXT_LIMIT) {
            truncated = true;
        }
        Long authorId = authorIdOf(row);
        return ReportVO.builder()
                .id(row.getId())
                .reporterId(row.getReporterId())
                .reporterNickname(row.getReporterNickname())
                .targetType(row.getTargetType())
                .targetId(row.getTargetId())
                .targetSummary(truncate(summaryOf(row), TARGET_SUMMARY_LIMIT, true))
                .targetAuthorId(authorId)
                .targetAuthorNickname(authorId == null ? null : nickByAuthor.get(authorId))
                .reasonType(row.getReasonType())
                .reasonText(reasonText)
                .reasonTextTruncated(truncated)
                .status(row.getStatus())
                .handlerId(row.getHandlerId())
                .handleTime(row.getHandleTime())
                .createTime(row.getCreateTime())
                .build();
    }

    /**
     * 按 targetType 取被举报对象的摘要。
     *
     * <p>逐条查会形成 N+1（一页 20 条 = 20 次查询），但这些 id 已在
     * {@code idx_target} 上、且管理端每页最多 50 条，绝对代价可控；
     * 为此再写一套批量 IN 查询并按类型分组，代码量翻倍而收益不明显。
     * 真要优化应等举报量级上来后再做。
     *
     * <p><b>2026-10-21 补齐 6 档</b>：原来 switch 只有 1~4，
     * 集市活动（6）与搭子（5）落到 default → 一律显示「内容已删除或不存在」。
     * 也就是说管理员打开一条集市举报，<b>看不到被举报的活动是什么</b>，
     * 只能凭 reasonType 猜 —— 这是「能收到但没法判」的典型。
     */
    private String summaryOf(ReportListVO row) {
        if (row.getTargetType() == null || row.getTargetId() == null) {
            return "";
        }
        Long id = row.getTargetId();
        String text = switch (row.getTargetType()) {
            case ReportTargetType.POST -> reportMapper.selectPostContent(id);
            case ReportTargetType.COMMENT -> reportMapper.selectCommentContent(id);
            case ReportTargetType.CHAT_MESSAGE -> reportMapper.selectChatMessageContent(id);
            case ReportTargetType.USER -> reportMapper.selectUserNickname(id);
            case ReportTargetType.MKT_ACTIVITY -> reportMapper.selectMktActivitySummary(id);
            /*
             * 5 搭子：buddy_post 表已建但无端点（MVP+ 第二批），
             * 没有可查的内容 —— 保持「已删除」这个诚实的结果，
             * 而不是硬塞一句模糊文案。
             */
            default -> null;
        };
        if (text == null || text.isBlank()) {
            // 内容已被删除或不存在：明说「已删除」而不是留空，
            // 管理员看到空白会以为是自己页面没加载出来
            return "内容已删除或不存在";
        }
        return text;
    }

    /**
     * 截断超长文本。
     *
     * @param ellipsis 是否追加省略号。摘要要加（原文被砍掉），
     *                 举报补充说明不加（前端另有 reasonTextTruncated 标记）
     */
    private String truncate(String text, int limit, boolean ellipsis) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return ellipsis ? text.substring(0, limit) + "…" : text.substring(0, limit);
    }
}