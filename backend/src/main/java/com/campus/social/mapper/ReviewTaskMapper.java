package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /** 按帖子查审核任务(uk_post 唯一;AI 机审回写时用) */
    @Select("SELECT * FROM review_task WHERE post_id = #{postId} AND is_deleted = 0 LIMIT 1")
    ReviewTask selectByIdForPost(@Param("postId") Long postId);

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
