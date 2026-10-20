package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Feedback;
import com.campus.social.vo.FeedbackAdminItemVO;
import com.campus.social.vo.FeedbackMineItemVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意见反馈 Mapper。
 *
 * <p>与 {@link ReportMapper} 同构,但刻意不共用:两者的幂等语义相反
 * (report 幂等去重、feedback 允许重复),一旦合并,「同一人只能一条」
 * 的唯一约束会顺着共用的 insert 逻辑漏到反馈表上。
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {

    /**
     * 插入反馈。
     *
     * <p>刻意不用 {@code ON DUPLICATE KEY UPDATE} / {@code INSERT IGNORE}:
     * 反馈表没有唯一键,两者在这里没意义;而 IGNORE 会把「content 超长、
     * category 越界」这类真实错误一并吞掉,变成静默丢数据。
     */
    @Insert("INSERT INTO feedback (user_id, category, content, media_json, status) "
            + "VALUES (#{userId}, #{category}, #{content}, #{mediaJson}, 0)")
    int insertFeedback(@Param("userId") Long userId, @Param("category") int category,
            @Param("content") String content, @Param("mediaJson") String mediaJson);

    /**
     * 取本连接最近一次 INSERT 的自增主键。
     *
     * <p>{@code insertFeedback} 用注解 SQL、没配 {@code useGeneratedKeys}，
     * 拿不到新主键。普通反馈靠 {@code selectLatestIdByUser} 回查，但学号申诉
     * 的 {@code user_id} 是 NULL、回查按 user_id 会查不到，故补一条
     * {@code LAST_INSERT_ID()}（同一事务同一连接，紧跟 insert 之后取值是可靠的）。
     */
    @Select("SELECT LAST_INSERT_ID()")
    Long selectLastInsertId();

    /**
     * 取该用户刚插入的那条 id。
     *
     * <p>{@code insertFeedback} 用的是注解 SQL 而非 MP 的 insert，
     * 拿不到自增主键，只能回查。因为允许重复提交，这里必须按 user_id
     * 限定 —— 查「全局最新一条」会在两人同时提交时把别人的 id 发回给用户。
     */
    @Select("SELECT id FROM feedback WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    Long selectLatestIdByUser(@Param("userId") Long userId);

    /**
     * 乐观锁处置:仅当 status=0(待处理)才改,避免两个管理员并发处理同一条。
     * 影响行数=0 即「已被他人处理」,service 层回 1062。
     */
    @Update("UPDATE feedback SET status = #{status}, handler_id = #{handlerId}, "
            + "handle_time = #{handleTime}, handle_note = #{handleNote}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int handle(@Param("id") Long id, @Param("status") int status, @Param("handlerId") Long handlerId,
            @Param("handleTime") LocalDateTime handleTime, @Param("handleNote") String handleNote);

    /** 待处理总数,供管理端入口红点 */
    @Select("SELECT COUNT(*) FROM feedback WHERE status = 0 AND is_deleted = 0")
    int countPending();

    /**
     * 管理端列表(游标分页,id 倒序)。
     *
     * <p>游标用 id 不用 create_time:create_time 可能撞同一秒,
     * 用它做游标会漏行或重复行。id 单调唯一,是唯一可靠的游标键。
     * 联表带出提交人昵称,省掉 Java 侧的 N+1。
     */
    @Select("<script>"
            + "SELECT f.*, u.nickname AS user_nickname FROM feedback f "
            + "LEFT JOIN user u ON u.id = f.user_id "
            + "WHERE f.is_deleted = 0 "
            + "<if test='status != null'>AND f.status = #{status} </if>"
            + "<if test='category != null'>AND f.category = #{category} </if>"
            + "<if test='kind != null and kind == 1'>AND f.content LIKE '【认证申诉】%' </if>"
            + "<if test='kind != null and kind == 0'>AND f.content NOT LIKE '【认证申诉】%' </if>"
            + "<if test='cursor != null'>AND f.id &lt; #{cursor} </if>"
            + "ORDER BY f.id DESC LIMIT #{limit}"
            + "</script>")
    List<FeedbackAdminItemVO> selectAdminList(@Param("status") Integer status,
            @Param("category") Integer category, @Param("kind") Integer kind,
            @Param("cursor") Long cursor, @Param("limit") int limit);

    /**
     * 我的反馈列表(游标分页,id 倒序,只取当前用户自己提交的)。
     *
     * <p>不联 user 表:提交人就是自己,昵称没意义;只投影用户侧需要的字段,
     * 不把 handler_id 这种处理人 id 透传给提交者。
     */
    @Select("<script>"
            + "SELECT f.id, f.category, f.content, f.media_json, f.status, "
            + "f.handle_note, f.handle_time, f.create_time FROM feedback f "
            + "WHERE f.user_id = #{userId} AND f.is_deleted = 0 "
            + "<if test='cursor != null'>AND f.id &lt; #{cursor} </if>"
            + "ORDER BY f.id DESC LIMIT #{limit}"
            + "</script>")
    List<FeedbackMineItemVO> selectByUser(@Param("userId") Long userId,
            @Param("cursor") Long cursor, @Param("limit") int limit);
}
