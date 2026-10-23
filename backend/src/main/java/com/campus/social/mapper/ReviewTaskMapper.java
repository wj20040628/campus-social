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
}
