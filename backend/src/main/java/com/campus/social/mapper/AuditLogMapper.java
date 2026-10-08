package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 取某个用户最近一条指定动作的审计记录。
     *
     * <p>用于在没有专用列时从留痕表反查：
     * {@code migration_v1.sql} 第 4 节只给 user 增了 4 列，
     * 没有 {@code verified_at} / {@code reject_reason}，
     * 而本轮硬约束是不许自行加减字段。这两个值改记在 {@code audit_log}
     * （多态、只增不删，已有 {@code idx_action} 索引）。
     *
     * @param targetType 目标类型，本处固定 user
     * @param targetId   目标 id，本处为用户 id
     */
    @Select("SELECT create_time, detail FROM audit_log "
            + "WHERE action = #{action} AND target_type = #{targetType} AND target_id = #{targetId} "
            + "ORDER BY id DESC LIMIT 1")
    AuditLog selectLatest(@Param("action") String action, @Param("targetType") String targetType,
            @Param("targetId") Long targetId);
}