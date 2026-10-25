package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /** 按帖子查审核任务(uk_post 唯一;AI 机审回写时用) */
    @Select("SELECT * FROM review_task WHERE post_id = #{postId} AND is_deleted = 0 LIMIT 1")
    ReviewTask selectByIdForPost(@Param("postId") Long postId);

    /**
     * 待审占坑(条件更新,2026-10-23 加,审查 P0-7):status=0 → newStatus。
     * 影响行=0 说明已被并发处理(AI+人审同时点通过,或两个管理员并发),
     * 调用方据此放弃 —— 代替「select→判→updateById」的非原子三连,
     * 后者在并发下会让同帖双扩散、通知双发。
     */
    @Update("UPDATE review_task SET status = #{newStatus}, reviewer_id = #{reviewerId}, "
            + "update_time = NOW() WHERE id = #{taskId} AND status = 0 AND is_deleted = 0")
    int casMarkProcessed(@Param("taskId") long taskId,
                         @Param("newStatus") int newStatus,
                         @Param("reviewerId") Long reviewerId);

    /** 作者自己的审核任务(经 post.author_id 关联),按 id 降序;LIMIT size+1,调用方判 hasMore */
    @Select("""
            <script>
            SELECT t.* FROM review_task t
            JOIN post p ON p.id = t.post_id AND p.is_deleted = 0
            WHERE t.is_deleted = 0 AND p.author_id = #{authorId}
            <if test="lastId != null"> AND t.id &lt; #{lastId}</if>
            ORDER BY t.id DESC LIMIT #{size}
            </script>
            """)
    List<ReviewTask> selectByAuthor(@Param("authorId") Long authorId,
                                    @Param("lastId") Long lastId,
                                    @Param("size") int size);

    // ---------------- 圈主协审(2026-10-24) ----------------
    // 升级机制不落列、不起 Job:「是否已升级」= create_time 是否早于截止线
    // (now - escalateHours)。窗口内的圈内任务归圈主,超窗后自然滑入平台队列。
    //
    // 【坑:XML 实体只在 <script> 模式下解码】2026-10-24 实测:计数查询没包
    // <script> 却写了 &lt;/&gt;=,实体被原样发给 MySQL 直接语法错误 500。
    // 无动态标签的 @Select 直接写 > 和 < 字符;只有带 <if> 的才包 <script>。

    /**
     * 圈主协审队列:我 owned 的圈内待审任务,仅升级窗口内(create_time ≥ 截止线)。
     * JOIN circle 走 owner_id 过滤——只认圈主,圈管理(role=2)不参与协审,
     * 与 approve/reject 的权限判定同口径。
     */
    @Select("""
            <script>
            SELECT t.* FROM review_task t
            JOIN post p ON p.id = t.post_id AND p.is_deleted = 0
            JOIN circle c ON c.id = p.circle_id AND c.is_deleted = 0 AND c.owner_id = #{ownerId}
            WHERE t.status = 0 AND t.is_deleted = 0
              AND t.create_time &gt;= #{cutoff}
            <if test="lastId != null"> AND t.id &lt; #{lastId}</if>
            ORDER BY t.id DESC LIMIT #{size}
            </script>
            """)
    List<ReviewTask> selectPendingForOwner(@Param("ownerId") Long ownerId,
                                           @Param("cutoff") java.time.LocalDateTime cutoff,
                                           @Param("lastId") Long lastId,
                                           @Param("size") int size);

    /** 圈主待审数(协审入口角标,与上一条同一 WHERE) */
    @Select("""
            SELECT COUNT(*) FROM review_task t
            JOIN post p ON p.id = t.post_id AND p.is_deleted = 0
            JOIN circle c ON c.id = p.circle_id AND c.is_deleted = 0 AND c.owner_id = #{ownerId}
            WHERE t.status = 0 AND t.is_deleted = 0 AND t.create_time >= #{cutoff}
            """)
    int countPendingForOwner(@Param("ownerId") Long ownerId,
                             @Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * 平台队列(管理员):非圈内任务 + 已升级(超窗)的圈内任务。
     * LEFT JOIN:帖子被删时 p.* 全空,circle_id IS NULL 成立,任务仍对管理员可见
     * (处置会因帖子不存在被拒,但至少不静默消失)。
     */
    @Select("""
            <script>
            SELECT t.* FROM review_task t
            LEFT JOIN post p ON p.id = t.post_id
            WHERE t.status = 0 AND t.is_deleted = 0
              AND (p.circle_id IS NULL OR t.create_time &lt; #{cutoff})
            <if test="lastId != null"> AND t.id &lt; #{lastId}</if>
            ORDER BY t.id DESC LIMIT #{size}
            </script>
            """)
    List<ReviewTask> selectPendingPlatform(@Param("cutoff") java.time.LocalDateTime cutoff,
                                           @Param("lastId") Long lastId,
                                           @Param("size") int size);

    /** 平台待审数(与上一条同一 WHERE;待办聚合角标用它,口径必须与队列一致) */
    @Select("""
            SELECT COUNT(*) FROM review_task t
            LEFT JOIN post p ON p.id = t.post_id
            WHERE t.status = 0 AND t.is_deleted = 0
              AND (p.circle_id IS NULL OR t.create_time < #{cutoff})
            """)
    int countPendingPlatform(@Param("cutoff") java.time.LocalDateTime cutoff);
}
