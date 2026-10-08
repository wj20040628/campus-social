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
     * 写入已读回执。<b>刻意不用 {@code ON DUPLICATE KEY UPDATE}</b>——
     * 唯一索引 {@code uk_ann_user(announcement_id, user_id)} 在这里是靠
     * <b>抛 DuplicateKeyException</b> 兜底的，不是靠「不报错」。
     *
     * <p>为什么不能用 {@code ON DUPLICATE KEY UPDATE id = id}：这是本模块踩过的坑。
     * 那种写法下唯一键冲突被 SQL 自己吞掉，既不抛异常，返回值也不可信——
     * mysql-connector-j 默认携带 {@code CLIENT_FOUND_ROWS}，
     * 「冲突且赋回原值」返回的是 1 而不是文档里写的 0，
     * 于是任何 {@code affected == 0} 分支都是死代码。表现为：
     * 重复标记已读时 {@code read_count} 被反复 +1，触达率虚高——
     * 而触达率正是管理员判断公告效果的主要依据。
     *
     * <p>换 {@code read_time = read_time} 或 {@code LAST_INSERT_ID(id)} 都不可靠：
     * 前者在同一秒内重复提交时值相同、退化成 affected=0；
     * 后者两种分支都返回 &gt;0，仍无法区分。
     *
     * <p>也不用 {@code INSERT IGNORE}：它会把截断、超范围、非空约束等
     * 真实错误一并降级成警告，变成静默的数据丢失。
     *
     * <p>结论：是否已读由 service 层前置的 {@link #selectOne} 判定；
     * 本方法只负责「真插一行，插重了让唯一键把异常抛出来」。
     */
    @Insert("INSERT INTO announcement_read (announcement_id, user_id) VALUES (#{announcementId}, #{userId})")
    int insertRead(@Param("announcementId") Long announcementId, @Param("userId") Long userId);

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
