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

    /**
     * 某帖子最近一次「圈子下架」留痕（2026-11-08）。
     * 已发布帖被圈主/圈管理直接下架时没有 review_task，作者侧原因从审计表反查。
     */
    @Select("SELECT * FROM audit_log WHERE action = 'circle_takedown_post' "
            + "AND target_type = 'post' AND target_id = #{postId} ORDER BY id DESC LIMIT 1")
    AuditLog selectLatestPostTakedown(@Param("postId") long postId);

    /**
     * 某用户在时间窗内的指定动作次数（2026-10-25 加，信誉分计分用）。
     * target_type 固定 user：report_upheld / account_ban 都以人为目标落审计。
     */
    @Select("SELECT COUNT(*) FROM audit_log "
            + "WHERE action = #{action} AND target_type = 'user' AND target_id = #{targetId} "
            + "AND create_time >= #{since}")
    int countUserActionsSince(@Param("action") String action, @Param("targetId") Long targetId,
            @Param("since") java.time.LocalDateTime since);

    /**
     * 批量版违规计数（管理端用户处置列表，2026-11-07 加）。
     *
     * <p>一次取回：近 N 天被判成立的举报数（信誉分扣分项）、近 N 天封禁次数、
     * 全时段封禁次数（页面上展示「累计封禁 N 次」）。
     * 三个值同源同一张表，拆成三次查询只会让同一页多两次往返。
     */
    @Select("<script>" +
            "SELECT target_id AS uid, " +
            "SUM(action = 'report_upheld' AND create_time &gt;= #{since}) AS upheld_cnt, " +
            "SUM(action = 'account_ban' AND create_time &gt;= #{since}) AS ban_window_cnt, " +
            "SUM(action = 'account_ban') AS ban_total_cnt " +
            "FROM audit_log WHERE target_type = 'user' " +
            "AND action IN ('report_upheld', 'account_ban') AND target_id IN " +
            "<foreach collection='ids' item='i' open='(' close=')' separator=','>#{i}</foreach> " +
            "GROUP BY target_id" +
            "</script>")
    java.util.List<java.util.Map<String, Object>> countViolationsForUsers(
            @Param("ids") java.util.Collection<Long> ids,
            @Param("since") java.time.LocalDateTime since);

    /**
     * 某用户的处置轨迹（2026-11-08，管理端用户详情用）。
     * 只取账号级动作：封禁/解封/禁言/解除/注销；帖子审核记录在别处看，
     * 混进来会把「这个人的处置历史」淹在内容流水里。
     */
    @Select("SELECT * FROM audit_log WHERE target_type = 'user' AND target_id = #{userId} "
            + "AND action IN ('account_ban','account_unban','account_mute','account_unmute',"
            + "'account_cancel','account_purge','report_upheld') "
            + "ORDER BY id DESC LIMIT #{limit}")
    java.util.List<AuditLog> selectUserTrail(@Param("userId") long userId, @Param("limit") int limit);
}