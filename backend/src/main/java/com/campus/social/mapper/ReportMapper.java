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
     * 抛 {@code DuplicateKeyException}，由 service 层捕获并回填
     * {@code duplicated = true} 与 message「已记录」。
     *
     * <p>刻意不用 {@code ON DUPLICATE KEY UPDATE}：那种写法下唯一键冲突
     * 被 SQL 自己吞掉且返回值不可靠（mysql-connector-j 默认带 CLIENT_FOUND_ROWS，
     * 冲突赋回原值也返回 1），无法区分「本次新插入」与「重复」，
     * 调用方的 {@code affected == 0} 分支就成了死代码。
     *
     * <p>也不用 INSERT IGNORE：它会把「截断、超范围、非空约束」等真实错误
     * 一并吞掉，变成静默的数据丢失。抛出来才知道哪条举报没存进去。
     */
    @Insert("INSERT INTO report (reporter_id, target_type, target_id, reason_type, reason_text, target_snapshot, status) "
            + "VALUES (#{reporterId}, #{targetType}, #{targetId}, #{reasonType}, #{reasonText}, #{targetSnapshot}, 0)")
    int insertReport(@Param("reporterId") Long reporterId, @Param("targetType") int targetType,
            @Param("targetId") Long targetId, @Param("reasonType") int reasonType,
            @Param("reasonText") String reasonText, @Param("targetSnapshot") String targetSnapshot);

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

    /**
     * 忽略动作:不改 status,仅记录处置人与备注(用于「已阅未处置」)。
     *
* <p>WHERE 里必须带 {@code AND handler_id IS NULL}：
     * 少了它，重复 ignore 的影响行数完全取决于两次调用是否落在同一秒
     * （{@code update_time = NOW()} 精确到秒，同秒时 MySQL 判为「值未变」返回 0，
     * 跨秒则返回 1）——同一行为两次请求给出不同答案，属静默的不一致。
     * 加上这个条件后：首次命中返回 1，重复必然返回 0，
     * service 层据此稳定回 409。
     *
     * <p>这里 handler_id 判空是安全的：ban/reject 走的是 {@link #handle}，
     * 处置后 status 变为 1/2，service 层已在进入本方法前拦掉，
     * 不会与「已阅但未处置」的记录混淆。
     */
    /*
     * 忽略也要写 handle_time（2026-10-24 加）。
     *
     * 原来只写 handler_id + handle_note，于是「已处理」里那条被忽略的举报
     * 显示不出处置时间 —— 管理员看到「处置结论：忽略」却不知道是什么时候忽略的，
     * 而时间正是这类回查记录最有用的一半。
     * handle_time 语义是「这条被处置的时刻」，忽略同样是一次处置，没有理由留空。
     * 没有任何后端逻辑依赖它为 NULL（全仓库只有 UI 读它）。
     */
    @Update("UPDATE report SET handler_id = #{handlerId}, handle_time = NOW(), "
            + "handle_note = #{handleNote}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0 AND handler_id IS NULL")
    int markIgnored(@Param("id") Long id, @Param("handlerId") Long handlerId,
            @Param("handleNote") String handleNote);

    /**
     * 待处理总数，供手机端审核页顶栏红点。
     *
     * <p>必须**同时**排除「已被忽略」的（2026-10-24 修）。
     * 忽略按契约不改 status（仍是 0），只写 handler_id —— 所以只看 status=0
     * 会把已忽略的算进待处理数，红点永远清不掉。
     */
    @Select("SELECT COUNT(*) FROM report WHERE status = 0 AND handler_id IS NULL AND is_deleted = 0")
    int countPending();

    /**
     * 同一目标的独立待处置举报人数（2026-10-24 加，ReportFoldService 阈值折叠用）。
     *
     * <p>DISTINCT reporter_id：同一人重复举报被 uk_report 唯一键天然挡住，
     * 这里再显式去重是双保险（防历史脏数据）；只数 status=0 且无人处理过的 ——
     * 管理员驳回/忽略后计数即清零，集中刷举报想再触发阈值需要新的独立举报人。
     */
    @Select("SELECT COUNT(DISTINCT reporter_id) FROM report "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 0 AND handler_id IS NULL AND is_deleted = 0")
    int countPendingReporters(@Param("targetType") int targetType, @Param("targetId") Long targetId);

    /**
     * 「举报用户且成立」的次数（2026-10-24 加，ReviewTrustService 前科判定用）。
     * target_type=1 时 target_id 就是被举报的 userId，走 idx_target 索引。
     */
    @Select("SELECT COUNT(*) FROM report "
            + "WHERE target_type = 1 AND target_id = #{userId} AND status = 1 AND is_deleted = 0")
    int countUpheldUserReports(@Param("userId") long userId);

    /**
     * 近 N 小时的全部举报（2026-10-25 加，自动封禁计数用）。
     * 量级假设：校园单日举报是十级；拉回 Java 侧做「目标归因到同一人」的
     * 分组——这个归因要查四种内容表，放 SQL 里是四路 JOIN 的维护灾难。
     * 调用方自行过滤状态（未被处置的待审 + 成立才计入，驳回/忽略不算）。
     */
    @Select("SELECT * FROM report WHERE is_deleted = 0 AND create_time > DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    java.util.List<com.campus.social.entity.Report> selectRecent(@Param("hours") int hours);

    /**
     * 管理端列表（游标分页，按 id 倒序）。
     *
     * <p>游标用 id 而不是 create_time：create_time 可能撞同一秒，
     * 用它做游标会漏行或重复行。id 单调且唯一，是唯一可靠的游标键。
     *
     * <p>联表带出举报人昵称，省掉 Java 侧的 N+1 查询。
     *
     * <p><b>handled 参数（2026-10-24 加）</b>：区分「待处理」与「已处理」。
     * 之所以不能只用 status 表达，是因为「忽略」按契约不改 status ——
     * 一条被忽略的举报 status 仍是 0，只凭 status 既过滤不掉它（待处理里阴魂不散），
     * 也表达不出它（已处理里看不到）。真正可靠的判定是 handler_id：
     * 三种处置（忽略/驳回/成立封禁）都会写它。
     * <ul>
     *   <li>{@code handled=false} → 待处理：status=0 且还没人看过</li>
     *   <li>{@code handled=true}  → 已处理：handler_id 非空（含被忽略的）</li>
     *   <li>{@code handled=null}  → 兼容旧调用：退回按 status 过滤</li>
     * </ul>
     */
    @Select("<script>"
            + "SELECT r.*, u.nickname AS reporter_nickname FROM report r "
            + "LEFT JOIN user u ON u.id = r.reporter_id "
            + "WHERE r.is_deleted = 0 "
            + "<if test='handled != null and handled'>AND r.handler_id IS NOT NULL </if>"
            + "<if test='handled != null and !handled'>AND r.status = 0 AND r.handler_id IS NULL </if>"
            + "<if test='handled == null'><if test='status != null'>AND r.status = #{status} </if></if>"
            + "<if test='cursor != null'>AND r.id &lt; #{cursor} </if>"
            + "ORDER BY r.id DESC LIMIT #{limit}"
            + "</script>")
    List<com.campus.social.vo.ReportListVO> selectAdminList(@Param("status") Integer status,
            @Param("handled") Boolean handled,
            @Param("cursor") Long cursor, @Param("limit") int limit);

    /** 被举报对象的摘要文本,供管理员在手机上快速判断 */
    @Select("SELECT content FROM chat_message WHERE id = #{id} AND is_deleted = 0")
    String selectChatMessageContent(@Param("id") Long id);

    /** 群消息摘要（targetType=8）。撤回后原文仍在库里，但用户已看不到，按「已删除」处理 */
    @Select("SELECT content FROM group_message WHERE id = #{id} AND is_deleted = 0 AND is_recalled = 0")
    String selectGroupMessageContent(@Param("id") Long id);

    @Select("SELECT content FROM post WHERE id = #{id} AND is_deleted = 0")
    String selectPostContent(@Param("id") Long id);

    @Select("SELECT content FROM comment WHERE id = #{id} AND is_deleted = 0")
    String selectCommentContent(@Param("id") Long id);

    @Select("SELECT nickname FROM user WHERE id = #{id} AND is_deleted = 0")
    String selectUserNickname(@Param("id") Long id);

    /**
     * 集市活动摘要（targetType=6）。
     *
     * <p>标题有值就用标题，没填标题时退到正文 —— 与前端列表卡的取值口径一致
     * （MktCard 里是 {@code title || mainText}）。管理端看到的第一行
     * 应该和举报人在广场上看到的是同一句话，否则他要回头对一遍才知道举报的是哪条。
     *
     * <p>用 CONCAT + COALESCE 而不是在 Java 里拼：一次查询拿回结果，
     * 避免把「取标题」和「取正文」写成一个可能漏掉 null 的两段逻辑。
     */
    @Select("SELECT COALESCE(NULLIF(title, ''), LEFT(content, 60)) "
            + "FROM mkt_activity WHERE id = #{id} AND soft_delete = 0")
    String selectMktActivitySummary(@Param("id") Long id);

    // ---- 作者解析：封禁对象是内容的作者，不是内容本身 ------------------------

    @Select("SELECT author_id FROM post WHERE id = #{id} AND is_deleted = 0")
    Long selectPostAuthorId(@Param("id") Long id);

    @Select("SELECT user_id FROM comment WHERE id = #{id} AND is_deleted = 0")
    Long selectCommentAuthorId(@Param("id") Long id);

    @Select("SELECT sender_id FROM chat_message WHERE id = #{id} AND is_deleted = 0")
    Long selectChatMessageSenderId(@Param("id") Long id);

    @Select("SELECT sender_id FROM group_message WHERE id = #{id} AND is_deleted = 0")
    Long selectGroupMessageSenderId(@Param("id") Long id);

    /**
     * 集市活动发布者（targetType=6）。
     *
     * <p>返回 {@code user_id} 而不是 {@code id}：后者是活动主键，
     * 拿它去封禁等于把举报人自己封了。用 {@code soft_delete = 0} 而非 is_deleted ——
     * mkt_activity 的软删列叫 soft_delete，与其它表不一致。
     */
    @Select("SELECT user_id FROM mkt_activity WHERE id = #{id} AND soft_delete = 0")
    Long selectMktActivityOwnerId(@Param("id") Long id);

    // ---- 举报成立时的内容下架（2026-10-21 修「下架不生效」） -------------------
    /*
     * 【为什么必须用显式 UPDATE，不能用 updateById(entity)】
     *
     * 项目在 application.yml 配了 MyBatis-Plus 逻辑删除：
     *   logic-delete-field: isDeleted
     *   logic-delete-value: 1 / logic-not-delete-value: 0
     *
     * 一旦字段被登记为逻辑删除字段，updateById 会**主动排除**它 ——
     * 框架认为该字段归逻辑删除机制管，普通更新不得触及。
     * 于是原来的 `c.setIsDeleted(1); updateById(c)` 静默失效：
     * 日志打出「下架成功」，数据库里 is_deleted 仍是 0，内容照常展示。
     * 这正是举报系统最不能出的问题 —— 判了等于没判。
     *
     * 显式 UPDATE 绕过该限制：SQL 直接写 is_deleted，不经过 MP 的字段过滤。
     * 全部带 `AND is_deleted = 0` 条件，保证只下架一次、返回真实影响行数。
     */

    /** 帖子下架：status 1→2（与机审驳回同口径） */
    @Update("UPDATE post SET status = 2, update_time = NOW() WHERE id = #{id} AND is_deleted = 0 AND status <> 2")
    int takedownPost(@Param("id") Long id);

    /** 评论下架：软删 + 置下架态 */
    @Update("UPDATE comment SET is_deleted = 1, status = 2, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int takedownComment(@Param("id") Long id);

    /** 聊天消息下架：软删隐藏（私聊内容只在双方会话里，软删即消失） */
    @Update("UPDATE chat_message SET is_deleted = 1, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int takedownChatMessage(@Param("id") Long id);

    /** 群消息下架：软删；群消息列表按 is_deleted=0 过滤，双方都看不到 */
    @Update("UPDATE group_message SET is_deleted = 1, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int takedownGroupMessage(@Param("id") Long id);

    /** 集市活动下架：管理端强制软删（不校验属主） */
    @Update("UPDATE mkt_activity SET soft_delete = 1 WHERE id = #{id} AND soft_delete = 0")
    int takedownMktActivity(@Param("id") Long id);

    // ---- 文件库（targetType=7，2026-10-27） --------------------------------

    @Select("SELECT user_id FROM file_asset WHERE id = #{id} AND is_deleted = 0")
    Long selectFileOwnerId(@Param("id") Long id);

    @Select("SELECT title FROM file_asset WHERE id = #{id} AND is_deleted = 0")
    String selectFileSummary(@Param("id") Long id);

    /** 文件下架：只认已过审，置 status=3 并留下理由 */
    @Update("UPDATE file_asset SET status = 3, reject_reason = '举报成立，管理员下架', "
            + "review_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND status = 1 AND is_deleted = 0")
    int takedownFile(@Param("id") Long id);

    // ---- 圈子（targetType=9，2026-10-29） ----------------------------------

    /**
     * 圈子举报的归因用户（圈主）。不按 status 过滤：举报处置完成后，
     * 管理端列表仍要靠它显示「被举报圈主」，过滤掉会让作者列变成空白。
     */
    @Select("SELECT owner_id FROM circle WHERE id = #{id} AND is_deleted = 0")
    Long selectCircleOwnerId(@Param("id") Long id);

    /** 圈子摘要（管理端列表用）：圈子表只有名称，直接取 name */
    @Select("SELECT name FROM circle WHERE id = #{id} AND is_deleted = 0")
    String selectCircleSummary(@Param("id") Long id);

    /**
     * 圈子下架：status 0→1（与封禁/解散同口径），只对正常圈生效。
     * 与 {@code CircleAdminServiceImpl.disbandCircle} 的差别：这里不校验圈主，
     * 由举报处置链路（已过管理员鉴权）调用。
     */
    @Update("UPDATE circle SET status = 1, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0 AND status = 0")
    int takedownCircle(@Param("id") Long id);

    /** 圈子下架时级联隐藏圈内帖子：status 1→2，与圈主解散同一口径 */
    @Update("UPDATE post SET status = 2, update_time = NOW() "
            + "WHERE circle_id = #{id} AND is_deleted = 0 AND status = 1")
    int takedownCirclePosts(@Param("id") Long id);
}
