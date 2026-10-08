package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Announcement;
import com.campus.social.vo.AnnouncementRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 查询列：公告字段 + 发布人三列（LEFT JOIN 省掉 Java 侧 N+1）。
     *
     * <p>列别名与 {@link AnnouncementRow} 的字段一一对应；
     * 改这里必须同步改那个类，否则 MyBatis 映射不到（会静默留 null，故在此显式说明）。
     */
    String COLUMNS = "SELECT a.id, a.title, a.content, a.category, a.publisher_id, a.school_id, "
            + "a.is_top, a.top_expire_time, a.status, a.publish_time, a.read_count, "
            + "a.create_time, a.update_time, "
            + "u.nickname AS publisher_nickname, u.avatar_url AS publisher_avatar, "
            + "u.verify_status AS publisher_verify "
            + "FROM announcement a LEFT JOIN user u ON u.id = a.publisher_id ";

    /** 前台可见性：已发布、未删、同校 */
    String FRONT_WHERE = " WHERE a.status = 1 AND a.is_deleted = 0 AND a.school_id = #{schoolId} ";

    /**
     * 置顶中的公告（AC-07：置顶必须排在首位直到 top_expire_time）。
     *
     * <p><b>为什么要单独查而不是 ORDER BY is_top DESC</b>：
     * 前台排序是「置顶优先 → publish_time 倒序 → id 倒序」，而游标只有一个
     * BIGINT 字段可用。若把置顶与普通公告混在一条流里按 id 游标翻页，
     * 第 1 页返回 [置顶(id=5), 普通(id=10), 普通(id=9)] 时 nextCursor=5，
     * 第 2 页按 {@code id < 5} 取数会把 id=9、10 整批漏掉——这是会静默丢行的分页 bug。
     *
     * <p>故拆成两条流：置顶流本来就是「少量置顶」这一产品语义下的有界集合，
     * 每页全量取回；游标只作用于普通流（严格 id 倒序），语义单一且不会漏行。
     */
    @Select(COLUMNS + FRONT_WHERE
            + " AND a.is_top = 1 AND a.top_expire_time > NOW() "
            + " ORDER BY a.publish_time DESC, a.id DESC LIMIT #{limit}")
    List<AnnouncementRow> selectPinnedFront(@Param("schoolId") Long schoolId, @Param("limit") int limit);

    /**
     * 普通（非置顶）公告流首页，严格 id 倒序，与游标口径一致。
     *
     * <p>三个带 {@code <if>} 的查询都必须整体包在 {@code <script>} 里：
     * 注解方式下 MyBatis 只在有 script 包裹时才解析动态标签，
     * 漏了会把 {@code <if test=...>} 当成字面量原样拼进 SQL，
     * 报 BadSqlGrammar。这类错编译期查不出、启动也不报错，
     * 只有真实带参调用才炸——所以凡用 {@code <if>} 一律包 script，不靠"记得"。
     */
    @Select("<script>" + COLUMNS + FRONT_WHERE
            + " AND NOT (a.is_top = 1 AND a.top_expire_time &gt; NOW()) "
            + "<if test='category != null'> AND a.category = #{category} </if>"
            + " ORDER BY a.id DESC LIMIT #{limit}</script>")
    List<AnnouncementRow> selectNormalFrontFirst(@Param("schoolId") Long schoolId,
            @Param("category") Integer category, @Param("limit") int limit);

    /** 普通流下一页：id 游标 + 可选分类过滤 */
    @Select("<script>" + COLUMNS + FRONT_WHERE
            + " AND NOT (a.is_top = 1 AND a.top_expire_time &gt; NOW()) "
            + "<if test='category != null'> AND a.category = #{category} </if>"
            + "<if test='cursor != null'> AND a.id &lt; #{cursor} </if>"
            + " ORDER BY a.id DESC LIMIT #{limit}</script>")
    List<AnnouncementRow> selectNormalFrontAfter(@Param("schoolId") Long schoolId,
            @Param("category") Integer category, @Param("cursor") Long cursor, @Param("limit") int limit);

    /** 后台列表：含草稿与已下架。草稿的 publish_time 为 NULL，按它排序会不稳定，故统一按 id 倒序 */
    @Select("<script>" + COLUMNS
            + " WHERE a.is_deleted = 0 "
            + "<if test='status != null'> AND a.status = #{status} </if>"
            + "<if test='cursor != null'> AND a.id &lt; #{cursor} </if>"
            + " ORDER BY a.id DESC LIMIT #{limit}</script>")
    List<AnnouncementRow> selectAdminList(@Param("status") Integer status,
            @Param("cursor") Long cursor, @Param("limit") int limit);

    /** 详情（含发布人昵称，供后台看草稿；前台可见性判定在 service 层） */
    @Select(COLUMNS + " WHERE a.id = #{id} AND a.is_deleted = 0")
    AnnouncementRow selectDetailWithPublisher(@Param("id") Long id);

    /** 已读人数 +1。原子自增,绝不读改写 */
    @Update("UPDATE announcement SET read_count = read_count + 1, update_time = NOW() WHERE id = #{id}")
    int incrReadCount(@Param("id") Long id);

    /** 某公告当前的已读用户 id（用于在线提醒范围判定） */
    @Select("SELECT user_id FROM announcement_read WHERE announcement_id = #{announcementId} AND is_deleted = 0")
    List<Long> selectReadUserIds(@Param("announcementId") Long announcementId);

    /** 置顶到期兜底：把已过期但 is_top 仍为 1 的记录落回 0（查询时过滤已生效，此处做数据归位） */
    @Update("UPDATE announcement SET is_top = 0, top_expire_time = NULL, update_time = NOW() "
            + "WHERE is_top = 1 AND top_expire_time IS NOT NULL AND top_expire_time <= NOW()")
    int expireTopMarks();
}