package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.entity.Report;
import com.campus.social.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 举报目标 → 归因用户（2026-10-25 从 ReportServiceImpl 抽出）。
 *
 * <p>处置路径（ReportServiceImpl.handle）与自动封禁路径（ReportAutoBanService）
 * 都要做同一件事：举报可能指向用户/帖子/评论/消息/集市活动，但**处罚与计分
 * 的对象永远是内容背后的作者**。这段 switch 只允许存在一份——两处各写一份
 * 必然漂移（新增 targetType 时漏改一处就是封错人）。
 *
 * <p>与原实现的差异：内容查不到时返回 null 而不是抛异常。处置路径需要
 * 「明确报错让管理员重新定位」（调用方自己抛），自动封禁路径需要
 * 「尽力而为，解析不了就跳过」——异常策略交还调用方，本类只负责解析。
 */
@Service
@RequiredArgsConstructor
public class ReportTargetResolver {

    private final ReportMapper reportMapper;

    /**
     * @return 举报归因到的用户 id；目标类型未上线（搭子）或内容已删时为 null
     */
    public Long resolveUserId(Report report) {
        return switch (report.getTargetType()) {
            case ReportTargetType.USER -> report.getTargetId();
            case ReportTargetType.POST -> reportMapper.selectPostAuthorId(report.getTargetId());
            case ReportTargetType.COMMENT -> reportMapper.selectCommentAuthorId(report.getTargetId());
            case ReportTargetType.CHAT_MESSAGE -> reportMapper.selectChatMessageSenderId(report.getTargetId());
            case ReportTargetType.MKT_ACTIVITY -> reportMapper.selectMktActivityOwnerId(report.getTargetId());
            default -> null; // 搭子等未上线类型
        };
    }
}
