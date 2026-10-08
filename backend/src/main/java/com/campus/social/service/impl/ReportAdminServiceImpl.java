package com.campus.social.service.impl;

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

    @Override
    public ReportAdminPageVO list(Integer status, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        List<ReportListVO> rows = reportMapper.selectAdminList(status, cursor, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<ReportListVO> page = hasMore ? rows.subList(0, limit) : rows;

        List<ReportVO> list = new ArrayList<>(page.size());
        for (ReportListVO row : page) {
            list.add(toVo(row));
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

    private ReportVO toVo(ReportListVO row) {
        boolean truncated = false;
        String reasonText = truncate(row.getReasonText(), REASON_TEXT_LIMIT, false);
        if (row.getReasonText() != null && row.getReasonText().length() > REASON_TEXT_LIMIT) {
            truncated = true;
        }
        return ReportVO.builder()
                .id(row.getId())
                .reporterId(row.getReporterId())
                .reporterNickname(row.getReporterNickname())
                .targetType(row.getTargetType())
                .targetId(row.getTargetId())
                .targetSummary(truncate(summaryOf(row), TARGET_SUMMARY_LIMIT, true))
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
     */
    private String summaryOf(ReportListVO row) {
        if (row.getTargetType() == null || row.getTargetId() == null) {
            return "";
        }
        Long id = row.getTargetId();
        String text = switch (row.getTargetType()) {
            case 2 -> reportMapper.selectPostContent(id);
            case 3 -> reportMapper.selectCommentContent(id);
            case 4 -> reportMapper.selectChatMessageContent(id);
            case 1 -> reportMapper.selectUserNickname(id);
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