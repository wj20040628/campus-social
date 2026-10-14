package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.MktActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 校园限时活动集市 —— mkt_activity 表。
 * 广场/我的发布均按 last_launch_time 倒序（最近激活在前），游标分页（lastId + LIMIT size+1）。
 * 所有属主操作均带 user_id 校验 + soft_delete=0。
 */
@Mapper
public interface MktActivityMapper extends BaseMapper<MktActivity> {

    /**
     * 广场查询：仅「投放中且未满员且未过期且开启」的活动。
     * 可选按一级分类过滤。按 last_launch_time DESC, id DESC 排序；
     * lastId 非空时取 id 更小的（游标向下翻页）。LIMIT size（调用方传 size+1 判 hasMore）。
     */
    @Select("""
            <script>
            SELECT * FROM mkt_activity
            WHERE soft_delete = 0 AND is_enable = 1 AND is_full = 0 AND expire_time &gt; NOW()
              <if test="category != null and category != ''"> AND category = #{category}</if>
              <if test="lastId != null"> AND id &lt; #{lastId}</if>
            ORDER BY last_launch_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MktActivity> selectPlaza(@Param("category") String category,
                                  @Param("lastId") Long lastId,
                                  @Param("limit") int limit);

    /** 我的发布：某用户未软删的活动，按 last_launch_time DESC 游标分页。 */
    @Select("""
            <script>
            SELECT * FROM mkt_activity
            WHERE user_id = #{userId} AND soft_delete = 0
              <if test="lastId != null"> AND id &lt; #{lastId}</if>
            ORDER BY last_launch_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MktActivity> selectByUser(@Param("userId") Long userId,
                                   @Param("lastId") Long lastId,
                                   @Param("limit") int limit);

    /** 开/关：属主校验。 */
    @Update("UPDATE mkt_activity SET is_enable = #{enable} WHERE id = #{id} AND user_id = #{userId} AND soft_delete = 0")
    int updateEnable(@Param("id") Long id, @Param("userId") Long userId, @Param("enable") int enable);

    /** 满员标记：属主校验。 */
    @Update("UPDATE mkt_activity SET is_full = #{full} WHERE id = #{id} AND user_id = #{userId} AND soft_delete = 0")
    int updateFull(@Param("id") Long id, @Param("userId") Long userId, @Param("full") int full);

    /** 重新激活：刷新 expire_time + last_launch_time + 重新开启。属主校验。
     *  last_launch_time 由调用方生成(JVM 东八区 LocalDateTime.now()),避免与数据库 NOW()
     *  产生秒级偏差,保证「刷新」后严格大于之前值。 */
    @Update("UPDATE mkt_activity SET expire_time = #{expireTime}, last_launch_time = #{launchTime}, is_enable = 1 " +
            "WHERE id = #{id} AND user_id = #{userId} AND soft_delete = 0")
    int reactivate(@Param("id") Long id,
                   @Param("userId") Long userId,
                   @Param("expireTime") java.time.LocalDateTime expireTime,
                   @Param("launchTime") java.time.LocalDateTime launchTime);

    /** 软删：属主校验。 */
    @Update("UPDATE mkt_activity SET soft_delete = 1 WHERE id = #{id} AND user_id = #{userId} AND soft_delete = 0")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}
