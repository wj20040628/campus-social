package com.campus.social.service;

import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;

/**
 * 举报（F4，上线门槛项）。
 *
 * <p><b>为什么在report 表闭环而不进 review_task</b>：
 * {@code review_task} 是帖子专用队列（{@code post_id BIGINT NOT NULL} + {@code uk_post}），
 * 而举报有 5 种 target_type，多数不是帖子。硬塞需要把一条已跑通的审核链路
 * 改造成多态，代价远大于收益。
 */
public interface ReportService {

    /** 提交举报。幂等：同一用户对同一目标重复提交不新增记录，返回 duplicated=true */
    ReportSubmitVO submit(long reporterId, ReportSubmitDTO dto);

    /** 管理端列表见 {@link ReportAdminService}（读写职责分开，避免 impl 破 300 行） */

    /**
     * 处置举报。
     *
     * @param action ban 成立并封禁 / reject 驳回 / ignore 忽略
     * @return 落库后的状态回执
     */
    ReportHandleVO handle(long adminId, long reportId, ReportHandleDTO dto);
}