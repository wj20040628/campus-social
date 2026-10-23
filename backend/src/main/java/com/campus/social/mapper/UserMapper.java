package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 批量取用户，键为 user id。
     *
     * <p>用于评论列表这类「一页N 条、每条都要作者信息」的场景：
     * 逐条 selectById 会把一次分页放大成 N 次查询。
     * MyBatis-Plus 的 {@code selectBatchIds} 直接可用，
     * 但这里显式声明是为了让调用方一眼看出「这个方法是为了消 N+1 而存在的」。
     */
    /**
     * 按学号查用户(账号体系重构:学号是主账号)。
     *
     * <p>用在登录 OR 匹配、注册查重、找回密码定位账号。学号存于 {@code username}
     * 与 {@code student_no} 两列(新账号同值),这里两列都查,避免历史数据
     * username 仍是旧主标识(邮箱/手机)而 student_no 为空时漏判。
     */
    @Select("SELECT id, username, password_hash, student_no, email, nickname, avatar_url, "
            + "verify_status, status, role, school_id, dept_id, grade_id, major_id, "
            + "stranger_msg_enabled, onboarding_step, follower_count, following_count, "
            + "unread_notify_count, joined_circle_count, create_time, update_time, is_deleted "
            + "FROM user WHERE is_deleted = 0 AND (username = #{studentNo} OR student_no = #{studentNo}) "
            + "LIMIT 1")
    User selectByStudentNo(@Param("studentNo") String studentNo);

    /**
     * 按安全凭证邮箱查用户(账号体系重构)。邮箱存库前已统一转小写,这里用精确匹配即可。
     */
    @Select("SELECT id, username, password_hash, student_no, email, nickname, avatar_url, "
            + "verify_status, status, role, school_id, dept_id, grade_id, major_id, "
            + "stranger_msg_enabled, onboarding_step, follower_count, following_count, "
            + "unread_notify_count, joined_circle_count, create_time, update_time, is_deleted "
            + "FROM user WHERE is_deleted = 0 AND email = #{email} LIMIT 1")
    User selectByEmail(@Param("email") String email);

    @Select("<script>"
            + "SELECT id, username, nickname, avatar_url, verify_status, status FROM user WHERE is_deleted = 0 "
            + "AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<User> selectByIds(@Param("ids") Collection<Long> ids);

    /** 原子自增/自减计数,防并发丢失 */
    @Update("UPDATE user SET follower_count = follower_count + #{delta}, update_time = NOW() WHERE id = #{userId}")
    int adjustFollowerCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET following_count = following_count + #{delta}, update_time = NOW() WHERE id = #{userId}")
    int adjustFollowingCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET unread_notify_count = GREATEST(0, unread_notify_count + #{delta}), update_time = NOW() WHERE id = #{userId}")
    int adjustUnreadNotifyCount(@Param("userId") Long userId, @Param("delta") int delta);

    /** 加入/退出圈子时原子自增/自减「加入的圈子数」,防并发丢失(与 follower_count 同口径) */
    @Update("UPDATE user SET joined_circle_count = GREATEST(0, joined_circle_count + #{delta}), update_time = NOW() WHERE id = #{userId}")
    int adjustJoinedCircleCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE user SET unread_notify_count = 0, update_time = NOW() WHERE id = #{userId}")
    int clearUnreadNotifyCount(@Param("userId") Long userId);

    /**
     * 未读数原子重算(2026-10-23 加,审查 P1-13):一条 SQL 内 count + 写回,
     * 不再「selectCount → setXxx → updateById 整行覆盖」。
     *
     * <p>旧写法的问题:读到的 unread 可能早于另一条 +1 提交,回写即把这个增量抹掉;
     * 且 updateById 会把整行(含 follower/following 等计数)写回,覆盖面更大。
     * 单语句子查询保证「读和写之间没有窗口」。
     */
    @Update("UPDATE user SET unread_notify_count = "
            + "(SELECT COUNT(*) FROM notify n WHERE n.user_id = #{userId} AND n.is_read = 0 AND n.is_deleted = 0), "
            + "update_time = NOW() WHERE id = #{userId}")
    int recomputeUnreadNotifyCount(@Param("userId") Long userId);

    /** 对账:从 relation 表重算某用户的 follower_count / following_count */
    @Update("UPDATE user u SET " +
            "  follower_count = (SELECT COUNT(*) FROM relation r WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  following_count = (SELECT COUNT(*) FROM relation r WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  update_time = NOW() " +
            "WHERE u.id = #{userId}")
    int reconcileCounts(@Param("userId") Long userId);

    /** 全量对账:一次把所有用户的 follower/following 重算(凌晨跑,简单可靠) */
    @Update("UPDATE user u SET " +
            "  follower_count = (SELECT COUNT(*) FROM relation r WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  following_count = (SELECT COUNT(*) FROM relation r WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0), " +
            "  update_time = NOW()")
    int reconcileAll();

    // ---------------------------------------------------------------- F2 校内认证

    /**
     * 写入学校归属。
     *
     * <p>dept_id / grade_id 是权威值，dept / grade 是同一次写入里从字典表取出的名称快照
     * （见User 实体注释）。快照与 id 在同一条 UPDATE 内落库，不分两次写，
     * 避免出现「id 已更新、名称还是旧的」这种半更新状态被列表页读到。
     *
     * <p>「归属是否填全」（AC-18）改判 dept_id 与 grade_id 是否非空：
     * 早期数据只有 dept 名称没有 id，若继续按名称判空，
     * 字典化之后这些老用户会被误判为「没填归属」而被拦在引导页。
     */
    @Update("UPDATE user SET school_id = #{schoolId}, dept_id = #{deptId}, dept = #{dept}, "
            + "grade_id = #{gradeId}, grade = #{grade}, "
            + "major_id = #{majorId}, update_time = NOW() "
            + "WHERE id = #{userId}")
    int updateSchoolProfile(@Param("userId") Long userId, @Param("schoolId") Long schoolId,
            @Param("deptId") Long deptId, @Param("dept") String dept,
            @Param("gradeId") Long gradeId, @Param("grade") String grade,
            @Param("majorId") Long majorId);

    /** 认证通过。WHERE 带 verify_status <> 1 兜住并发重复提交 */
    @Update("UPDATE user SET verify_status = 1, update_time = NOW() "
            + "WHERE id = #{userId} AND verify_status <> 1")
    int markVerified(@Param("userId") Long userId);

    /** 提交学号认证:置 2(审核中)并落 student_no。student_no 的 UNIQUE 是主防线 */
    @Update("UPDATE user SET verify_status = 2, student_no = #{studentNo}, update_time = NOW() "
            + "WHERE id = #{userId}")
    int markVerifyPending(@Param("userId") Long userId, @Param("studentNo") String studentNo);

    /** 驳回认证:置 3(被驳回)。队列就是 user 表本身,见 ARCHITECTURE §3 Q9.4 */
    @Update("UPDATE user SET verify_status = #{status}, update_time = NOW() WHERE id = #{userId}")
    int updateVerifyStatus(@Param("userId") Long userId, @Param("status") int status);

    /**
     * 驳回后改资料触发重新核验：3 → 2，且**必须换一个新学号**。
     *
     * <p><b>为什么学号是入参而不是沿用旧值</b>：原学号正是被驳回的原因
     * （已被人占用 / 与学籍不符），沿用它重排队列等于让管理员再看一遍
     * 同一个必然再被驳回的申请，纯粹浪费人工。强制换号把这个判断
     * 交给用户——他最清楚自己哪个学号是对的。
     *
     * <p><b>WHERE 带 verify_status = 3</b>：这是本轮最要紧的边界。
     * 没有它，已认证（1）的用户改个昵称资料就会被拉回待审队列，
     * 而管理员一个人在校园里根本不知道有人在等他 —— 那是比死循环更糟的故障
     * （已认证用户会突然失去发公告与搭话权限，且无提示）。
     * 状态 0/2 也一并被这个条件挡掉：0 是「还没开始」，不该被资料改动推进；
     * 2 已在队列里，重复推进无意义。
     *
     * <p>影响行数 0 = 状态不是 3（没被驳回），由 service 层据此决定要不要补审计。
     */
    @Update("UPDATE user SET verify_status = 2, student_no = #{studentNo}, update_time = NOW() "
            + "WHERE id = #{userId} AND verify_status = 3")
    int resubmitAfterReject(@Param("userId") Long userId, @Param("studentNo") String studentNo);

    /** 封禁/解封(举报处置 action=ban)。status:0正常 1冻结 */
    @Update("UPDATE user SET status = #{status}, update_time = NOW() WHERE id = #{userId}")
    int updateAccountStatus(@Param("userId") Long userId, @Param("status") int status);

    // ---------------------------------------------------------------- 学号核验审核（admin）

    /**
     * 待审的学号核验（verify_status = 2），Cursor 游标分页。
     *
     * <p><b>游标与排序键必须同向</b>：这里是 {@code ORDER BY update_time DESC, id DESC}
     * 配 {@code id < cursor}，与 {@code selectAdminList}（举报列表）同构。
     * 排序键含 update_time 而游标只用 id，是因为 update_time 会撞同一秒——
     * 用它当游标会漏行或重复行；id 单调唯一，是唯一可靠的游标键。
     * 两者的组合之所以成立，是因为 id DESC 与 update_time DESC 在
     * 「后提交的 id 更大」这个前提下同向。
     *
     * <p>只取审核需要的 6 列：OD-19 要求后台在 375px 手机上可用，
     * 而管理员判断真伪只需要学号、院系年级、昵称，其余列不下发。
     *
     * <p><b>列名必须起别名对齐 VO 属性名</b>：{@code map-underscore-to-camel-case}
     * 只能把 {@code student_no} 映射成 {@code studentNo}，
     * 但 {@code id -> userId} 和 {@code update_time -> submittedAt}
     * 是语义改名而非下划线转换，规则管不到。
     * 不起别名时两列会静默变成 null——列表照常返回、userId 全是空，
     * 而 nextCursor 恰好取自 userId，于是分页永远拉不满第二页且看不出报错。
     */
    @Select("<script>"
            + "SELECT id AS user_id, nickname, student_no, dept, grade, "
            + "update_time AS submitted_at FROM user "
            + "WHERE verify_status = 2 AND is_deleted = 0 AND status = 0 "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if>"
            + "ORDER BY update_time DESC, id DESC LIMIT #{limit}"
            + "</script>")
    List<com.campus.social.vo.AdminVerifyPendingItemVO> selectPendingVerify(
            @Param("cursor") Long cursor, @Param("limit") int limit);

    /**
     * 通过学号核验：2 → 1，乐观锁。
     *
     * <p>WHERE 里带 {@code verify_status = 2} 而不是复用
     * {@link #markVerified}（它的条件只是 {@code <> 1}，会把 0 未认证、
     * 3 已驳回的账号也一起置成已认证）。审核端点必须只认「正在审核中」这一种输入状态，
     * 否则一个误点就会给根本没提交过学号的人发认证。
     *
     * <p>影响行数 0 = 已被他人处置或状态不符，由 service 层回 404，
     * 与「乐观锁防两个管理员并发处置同一条」是同一件事。
     */
    @Update("UPDATE user SET verify_status = 1, update_time = NOW() "
            + "WHERE id = #{userId} AND verify_status = 2")
    int approveVerify(@Param("userId") long userId);

    /** 驳回学号核验：2 → 3，同样只认「审核中」这一种输入状态 */
    @Update("UPDATE user SET verify_status = 3, update_time = NOW() "
            + "WHERE id = #{userId} AND verify_status = 2")
    int rejectVerify(@Param("userId") long userId);

    // ---------------------------------------------------------------- F11 引导

    /**
     * 引导资料的全量幂等覆盖写。
     *
     * <p>早前这里带 {@code AND onboarding_step <> 3} 当幂等键,但前端是
     * 「每一步都调 complete」(Onboarding §7):第 1 步写完 identity 就把 step
     * 推到 3,第 2 步的 tags、第 3 步的 follows 会被这个条件吞掉(affected=0
     * 走 duplicate 短路),永远存不上——前端三步走完标签仍是空的。
     *
     * <p>去掉条件后,幂等性由「同数据重复写结果不变」保证;并发下同行 UPDATE
     * 仍由 InnoDB 行锁串行化,最后一次写胜出,数据一致。
     *
     * <p>与 {@link #updateSchoolProfile} 同口径：id 与名称快照在同一条 UPDATE 内写入。
     */
    @Update("UPDATE user SET school_id = #{schoolId}, dept_id = #{deptId}, dept = #{dept}, "
            + "grade_id = #{gradeId}, grade = #{grade}, "
            + "major_id = #{majorId}, "
            + "onboarding_step = 3, update_time = NOW() "
            + "WHERE id = #{userId}")
    int completeOnboarding(@Param("userId") Long userId, @Param("schoolId") Long schoolId,
            @Param("deptId") Long deptId, @Param("dept") String dept,
            @Param("gradeId") Long gradeId, @Param("grade") String grade,
            @Param("majorId") Long majorId);

    /** 单独推进引导进度(选完标签但未完成关注时用) */
    @Update("UPDATE user SET onboarding_step = #{step}, update_time = NOW() "
            + "WHERE id = #{userId} AND onboarding_step < #{step}")
    int advanceOnboardingStep(@Param("userId") Long userId, @Param("step") int step);

    // ---------------------------------------------------------------- 推荐

    /**
     * 推荐关注的人（确定性规则排序，PRD §9 明确排除 AI 智能匹配）。
     * 打分：同院系 4 > 同年级 2 > 已认证 1，末位 id DESC 保证同分结果稳定、翻页不抖动。
     * 「曾有互动」这一档冷启动期没有数据，降级用 follower_count 代理。
 *
     * <p>排除：自己、已关注(type=1)、已拉黑(type=3)、status!=0、管理员账号。
 *
     * <p><b>同院系/同年级改用 id 比较</b>（原实现比的是 dept / grade 名称字符串）：
     * 这是本轮字典化的核心动机。名称相等是脆弱的——「计算机学院」与「计科」，
     * 「2024级」与「2024 级」都表示同一个人群却比不相等，同院系推荐会漏掉一半人。
     * id 相等才是「同一维度」的准确表达。
     *
     * <p>COALESCE 回退到名称比较：字典化之前注册的老用户 dept_id 为 NULL，
     * 若只比 id 会让这批人永远拿不到同院系加分，等于静默降级了老用户的推荐质量。
     * 回退只在 id 为 NULL 时生效，新数据一律走 id 通道。
     *
     * <p>{@code commonTagCount} 用一条相关子查询算出，避免在 Java 侧做 N+1。
     */
    @Select("SELECT u.id, u.nickname, u.avatar_url, u.dept, u.grade, u.verify_status, u.follower_count, "
            + "(CASE WHEN #{deptId} IS NOT NULL AND u.dept_id = #{deptId} THEN 4 "
            + "      WHEN #{deptId} IS NULL AND u.dept = #{dept} AND u.dept IS NOT NULL THEN 4 ELSE 0 END) + "
            + "(CASE WHEN #{gradeId} IS NOT NULL AND u.grade_id = #{gradeId} THEN 2 "
            + "      WHEN #{gradeId} IS NULL AND u.grade = #{grade} AND u.grade IS NOT NULL THEN 2 ELSE 0 END) + "
            + "(CASE WHEN u.verify_status = 1 THEN 1 ELSE 0 END) AS score, "
            + "(SELECT COUNT(*) FROM user_tag ut WHERE ut.user_id = u.id AND ut.is_deleted = 0 "
            + " AND ut.tag_id IN (SELECT tag_id FROM user_tag WHERE user_id = #{me} AND is_deleted = 0)) AS common_tag_count "
            + "FROM user u "
            + "WHERE u.id <> #{me} AND u.status = 0 AND u.is_deleted = 0 AND u.role = 0 AND u.school_id = #{schoolId} "
            + "  AND NOT EXISTS (SELECT 1 FROM relation r "
            + "                  WHERE r.from_user_id = #{me} AND r.to_user_id = u.id AND r.type IN (1, 3) AND r.is_deleted = 0) "
            + "ORDER BY score DESC, u.follower_count DESC, u.id DESC "
            + "LIMIT #{limit}")
    List<com.campus.social.vo.RecommendUserRow> selectRecommendUsers(
            @Param("me") Long me,
            @Param("deptId") Long deptId,
            @Param("dept") String dept,
            @Param("gradeId") Long gradeId,
            @Param("grade") String grade,
            @Param("schoolId") Long schoolId,
            @Param("limit") int limit);

    /** 本校应触达人数(公告触达率分母) */
    @Select("SELECT COUNT(*) FROM user WHERE school_id = #{schoolId} AND status = 0 AND is_deleted = 0 AND role = 0")
    int countSchoolUsers(@Param("schoolId") Long schoolId);

    /** 谁还没看：NOT EXISTS 判未读,游标按 id 升序(未读名单从旧到新看更合理) */
    @Select("SELECT u.id, u.nickname, u.avatar_url, u.dept, u.grade FROM user u "
            + "WHERE u.school_id = #{schoolId} AND u.status = 0 AND u.is_deleted = 0 AND u.role = 0 "
            + "  AND u.id > #{cursor} "
            + "  AND NOT EXISTS (SELECT 1 FROM announcement_read r "
            + "                  WHERE r.announcement_id = #{announcementId} AND r.user_id = u.id AND r.is_deleted = 0) "
            + "ORDER BY u.id ASC LIMIT #{limit}")
    List<com.campus.social.vo.UnreadUserVO> selectUnreadUsers(@Param("announcementId") Long announcementId,
            @Param("schoolId") Long schoolId, @Param("cursor") Long cursor, @Param("limit") int limit);

    /** 审计:按昵称模糊反查操作人 id 列表(供日志按姓名筛选) */
    @Select("SELECT id FROM user WHERE is_deleted = 0 AND nickname LIKE CONCAT('%', #{kw}, '%')")
    List<Long> selectIdsByNicknameLike(@Param("kw") String kw);

    @Select("SELECT COUNT(*) FROM user u "
            + "WHERE u.school_id = #{schoolId} AND u.status = 0 AND u.is_deleted = 0 AND u.role = 0 "
            + "  AND NOT EXISTS (SELECT 1 FROM announcement_read r "
            + "                  WHERE r.announcement_id = #{announcementId} AND r.user_id = u.id AND r.is_deleted = 0)")
    int countUnreadUsers(@Param("announcementId") Long announcementId, @Param("schoolId") Long schoolId);

    /** 仅取通知偏好列(轻量,不拉整行) */
    @Select("SELECT notify_pref FROM user WHERE id = #{id}")
    String selectNotifyPrefById(@Param("id") Long id);

    /** 整列覆盖写通知偏好(前端传 JSON 字符串,如 {"disabled":[2,3]};空=恢复全开) */
    @Update("UPDATE user SET notify_pref = #{pref}, update_time = NOW() WHERE id = #{id}")
    int updateNotifyPref(@Param("id") Long id, @Param("pref") String pref);

    /**
     * 整列覆盖写主页封面（2026-10-20）。{@code coverUrl} 传 null 即清空回默认封面。
     *
     * <p><b>为什么不用 {@code updateById}</b>：MyBatis-Plus 默认的字段策略是
     * {@code NOT_NULL} —— 实体里为 null 的字段<b>不会进 SET 子句</b>。
     * 用它写封面时，「恢复默认」（setCoverUrl(null)）会静默失效：
     * 接口返回 200、响应体里 coverUrl 也是 null（那是内存里的实体），
     * 但库里那一列纹丝未动，用户刷新一下封面又回来了。
     * 这条路只能靠显式 SQL 写 null。
     */
    @Update("UPDATE user SET cover_url = #{coverUrl}, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int updateCoverUrl(@Param("id") Long id, @Param("coverUrl") String coverUrl);
}
