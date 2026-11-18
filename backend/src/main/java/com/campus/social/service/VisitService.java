package com.campus.social.service;

import com.campus.social.dto.VisitReportDTO;
import com.campus.social.vo.GuestVisitVO;

/**
 * 访客访问记录（2026-11-14）。
 *
 * <p>游客不建账号、不发 token，访问数据只能由前端匿名上报。
 * 这里只负责记录与聚合，不做身份识别。
 */
public interface VisitService {

    /** 进入页面：按 session_id 幂等插入，重复调用不会产生第二行 */
    void enter(VisitReportDTO dto, String ip, String userAgent);

    /** 心跳 / 结束上报：只推进停留时长，不回退 */
    void ping(VisitReportDTO dto);

    /** 管理总览的游客 / 扫码一组数字 */
    GuestVisitVO summary();
}