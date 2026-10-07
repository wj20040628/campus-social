package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.AuditLogVO;

/**
 * 审计日志:关键写操作留痕
 * 实现:同步写 audit_log(校园数据量小,同步 OK;量大后改异步 MQ)
 */
public interface AuditService {

    /** 记一条审计(无 target) */
    void record(long userId, String action, String ip);

    /** 记一条审计(带对象) */
    void record(long userId, String action, String targetType, Long targetId, String detail, String ip);

    /** 系统/定时任务(userId=0 表示系统) */
    void recordSystem(String action, String targetType, Long targetId, String detail);

    /**
     * 查询审计日志(admin):按 动作/操作人 过滤,时间倒序游标分页。
     * 语义:查的是【全量日志(admin 视角)】,不是"我操作的"——后台日志要看别人。
     */
    CursorPage<AuditLogVO> queryLogs(Long lastId, int size, String action, Long userId, String keyword);
}
