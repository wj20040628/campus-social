package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Topic;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

    /**
     * 幂等建话题:name 撞 uk_name 时累加 post_count。
     * 新建:INSERT(name, post_count=0);命中:ON DUPLICATE KEY UPDATE post_count=post_count+1。
     * 调用方可用返回值判定,但这里只关心"保证存在且计数+1",id 由调用方 selectById 兜底。
     */
    @Insert("INSERT INTO topic (name, post_count) VALUES (#{name}, 0) " +
            "ON DUPLICATE KEY UPDATE post_count = post_count + 1")
    int upsertByName(@Param("name") String name);

    /**
     * 回退某话题 post_count(编辑帖时移除话题,或删帖级联)。
     * GREATEST(0, …) 防止负数(历史数据/重复回退兜底)。
     */
    @org.apache.ibatis.annotations.Update("UPDATE topic SET post_count = GREATEST(0, post_count - 1), " +
            "update_time = NOW() WHERE id = #{topicId} AND is_deleted = 0")
    int decrementPostCount(@Param("topicId") Long topicId);
}
