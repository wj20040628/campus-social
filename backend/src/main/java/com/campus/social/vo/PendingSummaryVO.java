package com.campus.social.vo;

import lombok.Data;

/**
 * 管理端待办聚合（AdminSummaryController，openapi schema 同名）。
 *
 * <p>为什么需要：此前六个管理入口各自为政，每个队列的待审数
 * 只有点进对应页面才能看到——管理员必须逐页巡检才知道有没有活。
 * 本 VO 让抽屉/侧栏的管理入口一次拉全四条队列的待办数，
 * 「有没有活、先去哪页」一眼可见。
 */
@Data
public class PendingSummaryVO {

    /** 待处置举报数（report status=0 且 handler_id 为空） */
    private Integer reportPending;

    /** 待人审机审任务数（review_task status=0） */
    private Integer reviewPending;

    /** 待审核学号核验数（user verify_status=2） */
    private Integer verifyPending;

    /** 待处理意见反馈数（feedback status=0） */
    private Integer feedbackPending;

    /** 待审核文件数（file_asset status=0，2026-10-27） */
    private Integer filePending;
}
