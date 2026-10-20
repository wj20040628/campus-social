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
     * 查询审计日志(admin):按 动作/操作人/关键字/时间范围/操作人昵称 过滤,时间倒序游标分页。
     * 语义:查的是【全量日志(admin 视角)】,不是"我操作的"——后台日志要看别人。
     *
     * @param start 起始时间(yyyy-MM-dd HH:mm:ss),null 表示不限
     * @param end   截止时间(yyyy-MM-dd HH:mm:ss),null 表示不限
     * @param nickname 操作人昵称(模糊),null 表示不限;匹配不到用户时返回空结果
     */
    CursorPage<AuditLogVO> queryLogs(Long lastId, int size, String action, Long userId, String keyword,
                                    String start, String end, String nickname);
}
