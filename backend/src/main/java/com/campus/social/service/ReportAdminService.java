package com.campus.social.service;

import com.campus.social.vo.ReportAdminPageVO;

/**
 * 举报管理端查询（F4）。
 *
 * <p>与 {@link ReportService} 分开的原因：前者是「写」的编排（幂等插入、
 * 乐观锁处置、封禁联动），本接口是「读」的组装（联表、摘要截断、游标）。
 * 混在一个 impl 里会让那个类突破 300 行门禁，且两种职责的失败模式完全不同
 * ——写要担心并发与事务，读要担心 N+1 与分页漏行。
 */
public interface ReportAdminService {

    /**
     * 管理端举报列表。
     *
     * @param status 0待审 1成立 2驳回；null 表示全部
     * @param cursor 上一页最后一条的 id；null 表示第一页
     */
    ReportAdminPageVO list(Integer status, Boolean handled, Long cursor, int size);
}