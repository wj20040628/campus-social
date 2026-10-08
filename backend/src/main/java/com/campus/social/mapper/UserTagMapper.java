package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.UserTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface UserTagMapper extends BaseMapper<UserTag> {

    /**
     * 幂等插入(uk_user_tag 兜底)。
     * ON DUPLICATE KEY UPDATE id = id 等价「已存在则不报错」,
     * 但会把 is_deleted=1 的软删行复活——这正是覆盖写想要的效果。
     */
    @Insert("INSERT INTO user_tag (user_id, tag_id) VALUES (#{userId}, #{tagId}) "
            + "ON DUPLICATE KEY UPDATE is_deleted = 0, update_time = NOW()")
    int upsert(@Param("userId") Long userId, @Param("tagId") Long tagId);

    /**
     * 物理删除某人的全部标签行(全量覆盖写的第一步)。
     *
     * <p>为什么不用软删:uk_user_tag(user_id, tag_id) 不含 is_deleted,
     * 软删留行会让唯一键继续锁住。第二次覆盖同一标签时 upsert 虽不报错,
     * 但计数增减会与残留行对不上。故覆盖写必须彻底删行
     * ——与 RelationServiceImpl / ActivityServiceImpl 的处理方式一致。
     */
    @Delete("DELETE FROM user_tag WHERE user_id = #{userId}")
    int hardDeleteAllByUser(@Param("userId") Long userId);

    /** 我选中的 tagId 列表 */
    @Select("SELECT tag_id FROM user_tag WHERE user_id = #{userId} AND is_deleted = 0")
    List<Long> selectTagIdsByUser(@Param("userId") Long userId);

    /** 批量取用户标签对,避免逐个查 interest_tag 造成 N+1 */
    @Select("<script>"
            + "SELECT user_id, tag_id FROM user_tag "
            + "WHERE is_deleted = 0 AND user_id IN "
            + "<foreach collection='userIds' item='u' open='(' separator=',' close=')'>#{u}</foreach>"
            + "</script>")
    List<UserTag> selectByUserIds(@Param("userIds") List<Long> userIds);

    /** 与我标签重合的用户数(推荐关注的人打分用,单条 SQL 表达,不做算法) */
    @Select("SELECT COUNT(DISTINCT t2.user_id) FROM user_tag t1 "
            + "JOIN user_tag t2 ON t1.tag_id = t2.tag_id "
            + "WHERE t1.user_id = #{me} AND t2.user_id <> #{me} AND t1.is_deleted = 0 AND t2.is_deleted = 0")
    int countUsersSharingTags(@Param("me") Long me);
}
