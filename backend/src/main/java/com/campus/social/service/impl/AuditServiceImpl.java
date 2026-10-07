package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.AuditLog;
import com.campus.social.entity.User;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.vo.AuditLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志实现:同步写(校园数据量小,量大后改异步 MQ)
 * 失败仅打日志,不影响主流程(审计不能阻断业务)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    @Override
    public void record(long userId, String action, String ip) {
        write(userId, action, null, null, null, ip);
    }

    @Override
    public void record(long userId, String action, String targetType, Long targetId, String detail, String ip) {
        write(userId, action, targetType, targetId, detail, ip);
    }

    @Override
    public void recordSystem(String action, String targetType, Long targetId, String detail) {
        write(0L, action, targetType, targetId, detail, "system");
    }

    private void write(Long userId, String action, String targetType, Long targetId, String detail, String ip) {
        try {
            AuditLog log = new AuditLog();
            log.setUserId(userId == null || userId == 0 ? null : userId);
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setDetail(detail);
            log.setIp(ip);
            log.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不阻断主流程
            log.error("audit log insert fail: action={}, target={}/{}, err={}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    @Override
    public CursorPage<AuditLogVO> queryLogs(Long lastId, int size, String action, Long userId, String keyword) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>()
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(userId != null, AuditLog::getUserId, userId)
                // 关键字匹配 detail / ip / action(操作人姓名需先查 id,暂不匹配名字,够用)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(AuditLog::getDetail, keyword)
                        .or().like(AuditLog::getIp, keyword)
                        .or().like(AuditLog::getAction, keyword))
                .lt(lastId != null, AuditLog::getId, lastId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + (size + 1));
        List<AuditLog> rows = auditLogMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<AuditLog> page = rows.subList(0, Math.min(size, rows.size()));

        // 批量回填操作人(防 N+1);userId=null 的是系统任务,显示"系统"
        List<Long> uids = page.stream().map(AuditLog::getUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, User> byId = new java.util.HashMap<>();
        if (!uids.isEmpty()) {
            for (User u : userMapper.selectBatchIds(uids)) {
                byId.put(u.getId(), u);
            }
        }
        List<AuditLogVO> vos = page.stream().map(l -> {
            User op = l.getUserId() != null ? byId.get(l.getUserId()) : null;
            String name = l.getUserId() == null ? "系统"
                    : (op != null ? (op.getNickname() != null ? op.getNickname() : op.getUsername()) : "已删除用户");
            String avatar = (op != null) ? op.getAvatarUrl() : null;
            return AuditLogVO.builder()
                    .id(l.getId())
                    .userId(l.getUserId())
                    .operatorName(name)
                    .operatorAvatar(avatar)
                    .action(l.getAction())
                    .targetType(l.getTargetType())
                    .targetId(l.getTargetId())
                    .detail(l.getDetail())
                    .ip(l.getIp())
                    .createTime(l.getCreateTime())
                    .build();
        }).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }
}
