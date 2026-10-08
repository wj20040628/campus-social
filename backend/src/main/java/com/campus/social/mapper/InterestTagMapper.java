package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.InterestTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InterestTagMapper extends BaseMapper<InterestTag> {

    /**
     * 原子增减使用计数,防并发丢失。
     * 绝不读改写——「先 select 再 set」在并发下必然丢计数。
     */
    @Update("UPDATE interest_tag SET use_count = GREATEST(0, use_count + #{delta}), update_time = NOW() "
            + "WHERE id = #{tagId}")
    int adjustUseCount(@Param("tagId") Long tagId, @Param("delta") int delta);

    /**
     * 批量增减,用于标签全量覆盖时一次性对齐计数。
     * 单条 SQL 内的多个 VALUES 共享一条执行计划,比循环单条快一个数量级。
     */
    @Update("<script>"
            + "UPDATE interest_tag SET use_count = GREATEST(0, use_count + delta), update_time = NOW() "
            + "WHERE id IN "
            + "<foreach collection='tagIds' item='t' open='(' separator=',' close=')'>#{t}</foreach>"
            + "</script>")
    int adjustUseCountBatch(@Param("tagIds") List<Long> tagIds, @Param("delta") int delta);

    /** F11 引导候选:每组按 use_count 降序、sort 升序取前 perCategory 条(冷启动期 use_count 全 0,退化为按 sort,结果稳定可重现) */
    @org.apache.ibatis.annotations.Select("<script>"
            + "SELECT * FROM interest_tag WHERE is_deleted = 0 "
            + "<if test='category != null'>AND category = #{category} </if>"
            + "ORDER BY use_count DESC, sort ASC, id ASC"
            + "</script>")
    List<InterestTag> selectAllOrdered(@Param("category") Integer category);
}
