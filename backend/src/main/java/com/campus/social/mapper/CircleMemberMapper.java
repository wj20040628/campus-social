package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.CircleMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {

    /**
     * 加入圈子。并发重复加入由唯一键 uk_cm(circle_id,user_id) 抛
     * DuplicateKeyException 兜底，由 service 捕获后按幂等返回。
     *
     * <p>不能用 {@code ON DUPLICATE KEY UPDATE id = id} + 看返回值判断：
     * mysql-connector-j 默认携带 CLIENT_FOUND_ROWS，冲突赋回原值时返回 1 而非 0，
     * 重复点加入会把 member_count / joined_circle_count 反复 +1。
     */
    @Insert("INSERT INTO circle_member (circle_id, user_id, role) VALUES (#{circleId}, #{userId}, #{role})")
    int insertMember(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);

    /**
     * 物理删除某人对某圈子的成员行(退圈用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_cm(circle_id,user_id) 继续锁住,
     * 再次入会撞 Duplicate entry。退圈应彻底删行。
     */
    @Delete("DELETE FROM circle_member WHERE circle_id = #{circleId} AND user_id = #{userId} AND role = #{role}")
    int hardDelete(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);

    /** 圈内封禁:置 ban_status / ban_until(东八区,NULL=长期);解除时 until 传 null */
    @Update("UPDATE circle_member SET ban_status = #{ban}, ban_until = #{until}, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND user_id = #{userId} AND is_deleted = 0")
    int setBan(@Param("circleId") long circleId, @Param("userId") long userId,
               @Param("ban") int ban, @Param("until") LocalDateTime until);

    /** 成员角色调整(任命/取消圈管理、移交圈主时翻转 role) */
    @Update("UPDATE circle_member SET role = #{role}, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND user_id = #{userId} AND is_deleted = 0")
    int setRole(@Param("circleId") long circleId, @Param("userId") long userId, @Param("role") int role);

    /**
     * 圈内仍可登录的正常成员，圈管理优先、其余按入圈行 id 升序。
     * 注销级联选新圈主时用：优先接给圈管理，其次最早加入的普通成员。
     */
    @Select("SELECT cm.* FROM circle_member cm "
            + "JOIN user u ON u.id = cm.user_id AND u.is_deleted = 0 AND u.status = 0 "
            + "WHERE cm.circle_id = #{circleId} AND cm.is_deleted = 0 AND cm.role IN (2, 3) "
            + "  AND (cm.ban_status = 0 OR (cm.ban_until IS NOT NULL AND cm.ban_until < NOW())) "
            + "ORDER BY CASE WHEN cm.role = 2 THEN 0 ELSE 1 END, cm.id ASC")
    List<CircleMember> activeMembersOrdered(@Param("circleId") Long circleId);

    /** 圈子解散:物理删除全部成员行(绕过软删,避免 uk_cm 残留) */
    @Delete("DELETE FROM circle_member WHERE circle_id = #{circleId}")
    int hardDeleteAllByCircle(@Param("circleId") Long circleId);

    /**
     * 圈子解散前，把圈内每个正常成员行的 joined_circle_count 各减 1。
     * 必须放在删除成员行之前执行，否则 JOIN 找不到行。
     */
    @Update("UPDATE user u JOIN circle_member cm ON cm.user_id = u.id "
            + "SET u.joined_circle_count = GREATEST(0, u.joined_circle_count - 1), "
            + "    u.update_time = NOW() "
            + "WHERE cm.circle_id = #{circleId} AND cm.is_deleted = 0")
    int decrementJoinedCountsForCircle(@Param("circleId") Long circleId);
}
