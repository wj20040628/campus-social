package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.FileAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileAssetMapper extends BaseMapper<FileAsset> {

    /** 容量口径：待审 + 已过审；驳回/下架/删除都不占 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset "
            + "WHERE user_id = #{userId} AND status IN (0, 1) AND is_deleted = 0")
    long sumActiveBytes(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM file_asset "
            + "WHERE user_id = #{userId} AND status = 0 AND is_deleted = 0")
    int countPendingByUser(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM file_asset WHERE status = 0 AND is_deleted = 0")
    int countPendingAll();

    /** 自然日上传数：含已删除/驳回，防止「传了删、删了传」绕过日限 */
    @Select("SELECT COUNT(*) FROM file_asset WHERE user_id = #{userId} AND create_time >= CURDATE()")
    int countCreatedToday(@Param("userId") long userId);

    /** 近 N 天过审文件数（信誉分文件加分用） */
    @Select("SELECT COUNT(*) FROM file_asset "
            + "WHERE user_id = #{userId} AND status = 1 AND is_deleted = 0 "
            + "AND review_time >= #{since}")
    int countApprovedSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    @Select("<script>"
            + "SELECT f.* FROM file_asset f "
            + "<if test='favorites'>"
            + "JOIN file_favorite fav ON fav.file_id = f.id AND fav.user_id = #{me} AND fav.is_deleted = 0 "
            + "</if>"
            + "WHERE f.is_deleted = 0 "
            + "<choose>"
            + "  <when test='mine'>AND f.user_id = #{me} </when>"
            + "  <otherwise>AND f.status = 1 </otherwise>"
            + "</choose>"
            + "<if test='category != null and category != \"\"'>AND f.category = #{category} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (f.title LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR f.file_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "<choose>"
            + "  <when test='favorites'>ORDER BY fav.id DESC </when>"
            + "  <when test='hot'>ORDER BY f.download_count DESC, f.like_count DESC, f.id DESC </when>"
            + "  <otherwise>ORDER BY f.id DESC </otherwise>"
            + "</choose>"
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<FileAsset> selectPage(@Param("me") long me,
            @Param("mine") boolean mine,
            @Param("favorites") boolean favorites,
            @Param("category") String category,
            @Param("keyword") String keyword,
            @Param("hot") boolean hot,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM file_asset f "
            + "<if test='favorites'>"
            + "JOIN file_favorite fav ON fav.file_id = f.id AND fav.user_id = #{me} AND fav.is_deleted = 0 "
            + "</if>"
            + "WHERE f.is_deleted = 0 "
            + "<choose>"
            + "  <when test='mine'>AND f.user_id = #{me} </when>"
            + "  <otherwise>AND f.status = 1 </otherwise>"
            + "</choose>"
            + "<if test='category != null and category != \"\"'>AND f.category = #{category} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (f.title LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR f.file_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "</script>")
    long countPage(@Param("me") long me,
            @Param("mine") boolean mine,
            @Param("favorites") boolean favorites,
            @Param("category") String category,
            @Param("keyword") String keyword);

    @Select("<script>"
            + "SELECT * FROM file_asset WHERE is_deleted = 0 "
            + "<if test='status != null'>AND status = #{status} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR file_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "ORDER BY id DESC LIMIT #{offset}, #{size}"
            + "</script>")
    List<FileAsset> selectAdminPage(@Param("status") Integer status,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM file_asset WHERE is_deleted = 0 "
            + "<if test='status != null'>AND status = #{status} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR file_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "</script>")
    long countAdminPage(@Param("status") Integer status, @Param("keyword") String keyword);

    /** 浏览量：Redis 已按天去重，这里只做原子自增 */
    @Update("UPDATE file_asset SET view_count = view_count + 1, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int incrementView(@Param("id") long id);

    @Update("UPDATE file_asset SET download_count = download_count + 1, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int incrementDownload(@Param("id") long id);

    @Update("UPDATE file_asset SET like_count = GREATEST(0, like_count + #{delta}), update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int adjustLikeCount(@Param("id") long id, @Param("delta") int delta);

    @Update("UPDATE file_asset SET favorite_count = GREATEST(0, favorite_count + #{delta}), update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int adjustFavoriteCount(@Param("id") long id, @Param("delta") int delta);

    /**
     * 审核 CAS：只有待审才流转，防两个管理员并发处置同一条。
     * 影响行数 0 = 已被他人处置或状态不符。
     */
    @Update("UPDATE file_asset SET status = #{status}, reviewer_id = #{reviewerId}, "
            + "review_time = NOW(), reject_reason = #{reason}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0 AND is_deleted = 0")
    int casReview(@Param("id") long id, @Param("status") int status,
            @Param("reviewerId") long reviewerId, @Param("reason") String reason);

    /** 已过审文件下架：只认 status=1，重复下架返回 0 */
    @Update("UPDATE file_asset SET status = 3, reviewer_id = #{reviewerId}, "
            + "review_time = NOW(), reject_reason = #{reason}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 1 AND is_deleted = 0")
    int takedown(@Param("id") long id, @Param("reviewerId") long reviewerId,
            @Param("reason") String reason);

    /** 驳回/下架 7 天后删对象，保留行与审核记录 */
    @Select("SELECT * FROM file_asset WHERE status IN (2, 3) AND is_deleted = 0 "
            + "AND object_key <> '' AND update_time < #{before} LIMIT 200")
    List<FileAsset> selectObjectsToPurge(@Param("before") LocalDateTime before);

    @Update("UPDATE file_asset SET object_key = '', update_time = NOW() WHERE id = #{id}")
    int clearObjectKey(@Param("id") long id);
}
