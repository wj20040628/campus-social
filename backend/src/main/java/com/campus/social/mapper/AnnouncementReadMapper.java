package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.AnnouncementRead;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnnouncementReadMapper extends BaseMapper<AnnouncementRead> {

    /**
     * 幂等写入已读回执（AC-09 的数据库兜底）。
     *
     * <p>{@code uk_ann_user(announcement_id, user_id)} 命中时返回 0 且不新增行，
     * 调用方据此回填 {@code duplicated = true}、message「已记录」，
     * 并且<b>不</b>给 {@code announcement.read_count} 加 1
     * ——已读人数只在首次已读时递增。
     */
    @Insert("INSERT INTO announcement_read (announcement_id, user_id) VALUES (#{announcementId}, #{userId}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIdempotent(@Param("announcementId") Long announcementId, @Param("userId") Long userId);

    @Select("SELECT * FROM announcement_read WHERE announcement_id = #{announcementId} "
            + "AND user_id = #{userId} AND is_deleted = 0")
    AnnouncementRead selectOne(@Param("announcementId") Long announcementId, @Param("userId") Long userId);

    /** 我已读的公告 id 集合(列表页批量判 isRead,避免逐条查造成 N+1) */
    @Select("<script>"
            + "SELECT announcement_id FROM announcement_read WHERE user_id = #{userId} AND is_deleted = 0 "
            + "AND announcement_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Long> selectReadIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    /**
     * 我的已读条数（未读数 = 已发布总数 − 本函数结果）。
     * 只做 count 查询，不做全量写入（SPEC §6.3）。
     */
    @Select("SELECT COUNT(*) FROM announcement_read ar JOIN announcement a "
            + "ON a.id = ar.announcement_id AND a.status = 1 AND a.is_deleted = 0 "
            + "WHERE ar.user_id = #{userId} AND ar.is_deleted = 0 AND a.school_id = #{schoolId}")
    int countReadInSchool(@Param("userId") Long userId, @Param("schoolId") Long schoolId);

    /** 已发布且同校的公告总数 */
    @Select("SELECT COUNT(*) FROM announcement WHERE status = 1 AND is_deleted = 0 AND school_id = #{schoolId}")
    int countPublishedInSchool(@Param("schoolId") Long schoolId);

    /** 最新一条我未读的公告 id,供红点点击直达 */
    @Select("SELECT a.id FROM announcement a "
            + "WHERE a.status = 1 AND a.is_deleted = 0 AND a.school_id = #{schoolId} "
            + "  AND NOT EXISTS (SELECT 1 FROM announcement_read r "
            + "                  WHERE r.announcement_id = a.id AND r.user_id = #{userId} AND r.is_deleted = 0) "
            + "ORDER BY a.publish_time DESC, a.id DESC LIMIT 1")
    Long selectLatestUnreadId(@Param("userId") Long userId, @Param("schoolId") Long schoolId);
}
