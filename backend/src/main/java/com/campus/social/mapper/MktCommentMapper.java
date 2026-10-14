package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.MktComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 集市活动留言 —— mkt_comment 表。按 id 倒序游标分页。 */
@Mapper
public interface MktCommentMapper extends BaseMapper<MktComment> {

    @Select("""
            <script>
            SELECT * FROM mkt_comment
            WHERE activity_id = #{activityId} AND is_deleted = 0
              <if test="lastId != null"> AND id &lt; #{lastId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MktComment> selectByActivity(@Param("activityId") Long activityId,
                                      @Param("lastId") Long lastId,
                                      @Param("limit") int limit);
}
