package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportSubmitVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报提交（F4，上线门槛项）。
 *
 * <p>只放「提交」一个动作。管理端的列表与处置在
 * {@link AdminReportController}，用类级 {@code @RequireAdmin} 收口权限，
 * 避免两处各写一遍判断而漏一处。
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 提交举报。幂等：重复举报同一目标返回 200 且 {@code duplicated=true}，
     * 不报错——「我已经举报过了」对用户不是错误状态。
     */
    @PostMapping
    public Result<ReportSubmitVO> submit(@Valid @RequestBody ReportSubmitDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(reportService.submit(me, dto));
    }
}