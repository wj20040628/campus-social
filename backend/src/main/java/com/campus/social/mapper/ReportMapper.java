package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Report;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    /**
     * 幂等插入（AC-13 的数据库兜底）。
     *
     * <p>{@code uk_report(reporter_id, target_type, target_id)} 命中时
     * 返回 0 且不新增行，调用方据此回填 {@code duplicated = true} 与 message「已记录」。
     *
     * <p>不用 INSERT IGNORE：它会把「截断、超范围、非空约束」等真实错误一并吞掉，
     * 变成静默的数据丢失。ON DUPLICATE KEY UPDATE 只吞「唯一键冲突」这一种预期情况。
     */
    @Insert("INSERT INTO report (reporter_id, target_type, target_id, reason_type, reason_text, status) "
            + "VALUES (#{reporterId}, #{targetType}, #{targetId}, #{reasonType}, #{reasonText}, 0) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIdempotent(@Param("reporterId") Long reporterId, @Param("targetType") int targetType,
            @Param("targetId") Long targetId, @Param("reasonType") int reasonType,
            @Param("reasonText") String reasonText);

    /** 查同一人对同一目标的既有举报（幂等时回显原记录） */
    @Select("SELECT * FROM report WHERE reporter_id = #{reporterId} AND target_type = #{targetType} "
            + "AND target_id = #{targetId} AND is_deleted = 0")
    Report selectByTarget(@Param("reporterId") Long reporterId, @Param("targetType") int targetType,
            @Param("targetId") Long targetId);

    /**
     * 乐观锁处置：仅当 status=0（待审）才改，避免两个管理员并发处置同一条。
     * 影响行数=0 即「已被他人处置」，调用方回 409。
     */
    @Update("UPDATE report SET status = #{status}, handler_id = #{handlerId}, handle_time = #{handleTime}, "
            + "handle_note = #{handleNote}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int handle(@Param("id") Long id, @Param("status") int status, @Param("handlerId") Long handlerId,
            @Param("handleTime") LocalDateTime handleTime, @Param("handleNote") String handleNote);

    /** 忽略动作:不改 status,仅记录处置人与备注(用于「已阅未处置」) */
    @Update("UPDATE report SET handler_id = #{handlerId}, handle_note = #{handleNote}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int markIgnored(@Param("id") Long id, @Param("handlerId") Long handlerId,
            @Param("handleNote") String handleNote);

    /** status=0 的总数,供手机端审核页顶栏红点 */
    @Select("SELECT COUNT(*) FROM report WHERE status = 0 AND is_deleted = 0")
    int countPending();

    /**
     * 管理端列表（游标分页，按 create_time 倒序、id 倒序）。
     *
     * <p>游标用 id 而不是 create_time：create_time 可能撞同一秒，
     * 用它做游标会漏行或重复行。id 单调且唯一，是唯一可靠的游标键。
     *
     * <p>联表带出举报人昵称，省掉 Java 侧的 N+1 查询。
     */
    @Select("<script>"
            + "SELECT r.*, u.nickname AS reporter_nickname FROM report r "
            + "LEFT JOIN user u ON u.id = r.reporter_id "
            + "WHERE r.is_deleted = 0 "
            + "<if test='status != null'>AND r.status = #{status} </if>"
            + "<if test='cursor != null'>AND r.id &lt; #{cursor} </if>"
            + "ORDER BY r.id DESC LIMIT #{limit}"
            + "</script>")
    List<com.campus.social.vo.ReportListVO> selectAdminList(@Param("status") Integer status,
            @Param("cursor") Long cursor, @Param("limit") int limit);

    /** 被举报对象的摘要文本,供管理员在手机上快速判断 */
    @Select("SELECT content FROM chat_message WHERE id = #{id} AND is_deleted = 0")
    String selectChatMessageContent(@Param("id") Long id);

    @Select("SELECT content FROM post WHERE id = #{id} AND is_deleted = 0")
    String selectPostContent(@Param("id") Long id);

    @Select("SELECT content FROM comment WHERE id = #{id} AND is_deleted = 0")
    String selectCommentContent(@Param("id") Long id);

    @Select("SELECT nickname FROM user WHERE id = #{id} AND is_deleted = 0")
    String selectUserNickname(@Param("id") Long id);

    // ---- 作者解析：封禁对象是内容的作者，不是内容本身 ------------------------

    @Select("SELECT author_id FROM post WHERE id = #{id} AND is_deleted = 0")
    Long selectPostAuthorId(@Param("id") Long id);

    @Select("SELECT user_id FROM comment WHERE id = #{id} AND is_deleted = 0")
    Long selectCommentAuthorId(@Param("id") Long id);

    @Select("SELECT sender_id FROM chat_message WHERE id = #{id} AND is_deleted = 0")
    Long selectChatMessageSenderId(@Param("id") Long id);
}
