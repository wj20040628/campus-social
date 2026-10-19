package com.campus.social.service;

import com.campus.social.dto.FeedbackHandleDTO;
import com.campus.social.dto.FeedbackSubmitDTO;
import com.campus.social.vo.FeedbackAdminPageVO;
import com.campus.social.vo.FeedbackSubmitVO;

/**
 * 意见反馈（2026-10-18，导航抽屉「意见反馈」）。
 *
 * <p>读写分两个接口（与 {@link ReportService} / {@link ReportAdminService} 同理由）：
 * 提交是「写」的编排（媒体序列化、敏感词、落库），管理端是「读」的组装
 * （联表昵称、游标分页）。混在一个 impl 里会让那个类破 300 行门禁，
 * 而且两种职责的失败模式完全不同 —— 写要担心并发与事务，读要担心 N+1 与漏行。
 */
public interface FeedbackService {

    /**
     * 提交反馈。
     *
     * <p><b>不幂等</b>：同一个人可以重复提交（同一问题提两次是常事，
     * 反而说明用户很在意）。这与举报的幂等语义相反，故不共用 ReportService。
     */
    FeedbackSubmitVO submit(long userId, FeedbackSubmitDTO dto);

    /** 管理端列表（游标分页） */
    FeedbackAdminPageVO list(Integer status, Long cursor, int size);

    /**
     * 处置反馈。
     *
     * @param action adopt 采纳 / ignore 忽略
     */
    FeedbackSubmitVO handle(long adminId, long feedbackId, FeedbackHandleDTO dto);
}
