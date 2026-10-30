package com.campus.social.service.impl;

import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 举报服务门面（F4，2026-10-30 拆分）。
 *
 * <p>提交委托 {@link ReportSubmitService}，处置委托 {@link ReportReviewService}。
 * 事务边界留在门面：提交里的折叠计数需要读到本条未提交的行，
 * 处置里的状态更新、封禁、审计、通知必须同进同退。
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportSubmitService submitService;
    private final ReportReviewService reviewService;

    @Override
    @Transactional
    public ReportSubmitVO submit(long reporterId, ReportSubmitDTO dto) {
        return submitService.submit(reporterId, dto);
    }

    @Override
    @Transactional
    public ReportHandleVO handle(long adminId, long reportId, ReportHandleDTO dto) {
        return reviewService.handle(adminId, reportId, dto);
    }
}
