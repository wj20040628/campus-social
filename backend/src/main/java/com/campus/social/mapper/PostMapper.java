package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Post;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /** 点赞/取消赞:事务内原子 ±1 */
    @Update("UPDATE post SET like_count = like_count + #{delta}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    @Update("UPDATE post SET comment_count = comment_count + #{delta}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 收藏/取消收藏:事务内原子 ±1,与 adjustLikeCount 同构。
     *
     * <p>用「列 + delta」而不是「先读再写」:两个人同时收藏时,
     * 读-改-写会互相覆盖,少算一次;交给数据库做自增才不会丢。
     */
    @Update("UPDATE post SET favorite_count = favorite_count + #{delta}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustFavoriteCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 浏览量:进详情原子 +1 */
    @Update("UPDATE post SET view_count = view_count + 1, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int adjustViewCount(@Param("postId") Long postId);

    @Update("UPDATE post SET hot_score = #{hotScore}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int updateHotScore(@Param("postId") Long postId, @Param("hotScore") double hotScore);

    /**
     * 热度分增量(2026-10-23 加,审查 P1-15):数据库侧自增/自减,绝不读-改-写。
     *
     * <p>旧写法是「查出来 +1.0 再绝对值写回」:并发点赞 N 次只有 1-2 次增量真正落地,
     * 且与走 {@code col = col ± 1} 的 like_count 必然发散(热度对不上点赞数)。
     * GREATEST(0, ...) 防减到负数(取消点赞比点赞多时)。
     *
     * <p>绝对值写回版 {@link #updateHotScore} 保留给 ReconcileJob 全量重算用。
     */
    @Update("UPDATE post SET hot_score = GREATEST(0, hot_score + #{delta}), update_time = NOW() "
            + "WHERE id = #{postId} AND is_deleted = 0")
    int adjustHotScore(@Param("postId") Long postId, @Param("delta") double delta);

    /**
     * 一批圈子各自的帖数与最新一帖时间(圈子列表副行「最近动态」用)。
     *
     * <p>一次查回整页圈子,避免 N+1。用 Map 而不是实体:这是聚合结果,
     * 没有对应的实体可映射;列名用下划线别名,读取时按 get("circle_id") 取。
     *
     * <p>status=1 是「已发布可见」口径,与帖子搜索/列表一致 —— 草稿与被屏蔽的不计,
     * 否则会出现「显示 3 条帖子但一个都点不开」。
     */
    @Select("<script>" +
            "SELECT circle_id AS circle_id, COUNT(*) AS post_cnt, MAX(create_time) AS last_at " +
            "FROM post WHERE circle_id IN " +
            "<foreach collection='circleIds' item='i' open='(' close=')' separator=','>#{i}</foreach> " +
            "AND status = 1 AND is_deleted = 0 GROUP BY circle_id" +
            "</script>")
    List<java.util.Map<String, Object>> postStatsByCircles(@Param("circleIds") Collection<Long> circleIds);

    /**
     * 对账:重算 like_count(真实点赞数)+ hot_score(按热度公式) —— <b>全量</b>。
     *
     * <p>只在每天凌晨的低峰对账里调用。全量重算的代价是「帖子数 × 一次索引查找」，
     * 数据量上来后是一次不容忽视的长事务，<b>不要</b>拿它做高频刷新。
     *
     * <p>为什么不再写 {@code update_time = NOW()}：
     * 该列的 DDL 是 {@code ON UPDATE CURRENT_TIMESTAMP}，值真的变了才会自动更新。
     * 显式赋值等于强行让每一行都产生新版本，即使 like_count 和 hot_score 完全没变 ——
     * 白白产生 redo/undo、脏页刷盘和表空间膨胀。去掉之后 MySQL 会跳过未变化的行，
     * 对账的写放大自动降到「真正有偏差的帖子数」。
     */
    @Update("UPDATE post p SET " +
            "  like_count = (SELECT COUNT(*) FROM `like` l WHERE l.target_type = 1 AND l.target_id = p.id AND l.is_deleted = 0), " +
            "  hot_score = (p.like_count + 2 * p.comment_count) / " +
            "             POWER(GREATEST(1, TIMESTAMPDIFF(HOUR, p.create_time, NOW()) + 2), 1.5) " +
            "WHERE p.status = 1 AND p.is_deleted = 0")
    int reconcileHotAndLikes();

    /**
     * 热度分增量刷新:只算最近 7 天发布的帖子。
     *
     * <p>hot_score 的公式对时间做了衰减（分母是「发布至今小时数」的 1.5 次幂），
     * 所以这条分值的意义只存在于发布后的头几天：一个 30 天前的老帖，
     * 无论 10 分钟刷一次还是 1 小时刷一次，都远在热门榜之外。
     *
     * <p>用 7 天而不是 1 天，是为了兜住「周末发的帖周一还在被互动」这种情况。
     * 7 天外的 like_count 漂移由每天 3 点的 {@link #reconcileHotAndLikes()} 兜底。
     */
    @Update("UPDATE post p SET " +
            "  like_count = (SELECT COUNT(*) FROM `like` l WHERE l.target_type = 1 AND l.target_id = p.id AND l.is_deleted = 0), " +
            "  hot_score = (p.like_count + 2 * p.comment_count) / " +
            "             POWER(GREATEST(1, TIMESTAMPDIFF(HOUR, p.create_time, NOW()) + 2), 1.5) " +
            "WHERE p.status = 1 AND p.is_deleted = 0 " +
            "  AND p.create_time > DATE_SUB(NOW(), INTERVAL 7 DAY)")
    int refreshRecentHotScore();

    /** 对账:重算 comment_count(真实评论数)。同样不写 update_time,理由见上。 */
    @Update("UPDATE post p SET " +
            "  comment_count = (SELECT COUNT(*) FROM comment c WHERE c.post_id = p.id AND c.is_deleted = 0 AND c.status = 1) " +
            "WHERE p.is_deleted = 0")
    int reconcileComments();

    /** 圈内置顶:置单帖 is_top(0/1) */
    @Update("UPDATE post SET is_top = #{isTop}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int setIsTop(@Param("postId") long postId, @Param("isTop") int isTop);

    /** 同圈其它置顶归 0(保证圈内同时只有 1 个置顶;传 keepPostId 跳过自己) */
    @Update("UPDATE post SET is_top = 0, update_time = NOW() "
            + "WHERE circle_id = #{circleId} AND is_top = 1 AND id <> #{keepPostId} AND is_deleted = 0")
    int clearTopInCircle(@Param("circleId") long circleId, @Param("keepPostId") long keepPostId);

    /** 圈管理下架:status=2(软,作者可自查),非物理删 */
    @Update("UPDATE post SET status = 2, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int takedown(@Param("postId") long postId);

    /** 移帖:改归属圈(toCircleId 为 null=移回广场) */
    @Update("UPDATE post SET circle_id = #{toCircleId}, update_time = NOW() WHERE id = #{postId} AND is_deleted = 0")
    int moveCircle(@Param("postId") long postId, @Param("toCircleId") Long toCircleId);

    /** 物理删除帖子(绕过 @TableLogic),级联数据在 service 里清 */
    @Delete("DELETE FROM post WHERE id = #{postId}")
    int hardDelete(@Param("postId") Long postId);

    /**
     * 解散圈子时级联软删圈内全部帖子(绕过 @TableLogic,保留数据可审计)。
     * status=2(已删):从所有 Feed 入口(综合/话题/热门/我的,均按 status=1 过滤)
     * 一并隐藏,残留帖不再露出。与 Post.takedown 的 status=2 同语义但批量。
     */
    @Update("UPDATE post SET status = 2, update_time = NOW() WHERE circle_id = #{circleId} AND is_deleted = 0")
    int invalidateCirclePosts(@Param("circleId") long circleId);

    /**
     * 恢复解散的圈子(对称 invalidateCirclePosts):把圈内帖子 status=2 还原回 1。
     * 仅针对 circle_id=X 的帖子,不影响其它圈。只处理「被解散级联改成的 status=2」,
     * 但 SQL 不区分来源,因此调用方(restoreCircle)只在 status=1 的圈上调,
     * 而 status=1 的圈在广场不可见,其内帖子的 status=2 只能来自解散(正常圈的下架帖
     * 也是 status=2 但圈 status=0,不会被误伤)。
     */
    @Update("UPDATE post SET status = 1, update_time = NOW() WHERE circle_id = #{circleId} AND status = 2 AND is_deleted = 0")
    int restoreCirclePosts(@Param("circleId") long circleId);

    /**
     * 某用户发的动态数(本人主页头部「动态」数字用)。
     * 口径与 PostFeedQueryService.myPosts 本人视角一致:status IN (0,1,2) 且未删。
     * 现查 post 表而非依赖冗余计数列 —— 列漂移时列表是对的、头部也会跟着对。
     */
    @Select("SELECT COUNT(*) FROM post WHERE author_id = #{authorId} AND status IN (0,1,2) AND is_deleted = 0")
    int countByAuthor(@Param("authorId") Long authorId);

    /**
     * 某用户公开可见的动态数(2026-10-20,他人主页头部「动态」数字用)。
     * 只数 status=1:看别人主页时,审核中/已驳回的帖子既不出现在列表,
     * 头部数字也不该把它们算进去 —— 否则「数字 20、列表 15」对不上。
     * 本人视角仍走 countByAuthor(含 0/2,与本人列表口径一致)。
     */
    @Select("SELECT COUNT(*) FROM post WHERE author_id = #{authorId} AND status = 1 AND is_deleted = 0")
    int countPublicByAuthor(@Param("authorId") Long authorId);

    /**
     * 某人所有帖子的获赞总数(个人主页「获赞」指标)。
     *
     * <p><b>为什么实时 SUM 而不给 user 表加冗余列</b>:
     * 冗余列要在「点赞 +1 / 取消 -1 / 删帖扣减 / 历史回填」四处维护,
     * 漏一处数字就永久对不上且很难发现。SUM 只有一个数据源 post.like_count,
     * 结构上不可能漂移。校园规模单用户最多几百条帖,走 author_id 索引是毫秒级。
     *
     * <p><b>为什么不分本人/他人视角</b>(对比 countByAuthor / countPublicByAuthor):
     * 动态数要区分,是因为作者能看到自己的待审/驳回帖,数字必须和列表对得上;
     * 而获赞是**别人**给的动作,别人看不到待审帖就点不了赞,两个口径实际相等。
     *
     * <p>COALESCE 兜住空集:新用户一条帖子都没有时 SUM 返回 NULL,
     * 不包一层会让前端拿到 null 渲染成空白。
     */
    @Select("SELECT COALESCE(SUM(like_count), 0) FROM post WHERE author_id = #{authorId} AND is_deleted = 0")
    int sumLikeCountByAuthor(@Param("authorId") Long authorId);

    /**
     * 帖子全文搜索(MATCH/AGAINST,中文走 ngram 分词器)。
     * keyword 在 service 层做"引号等特殊字符过滤"后传入(MyBatis #{} 参数化,无注入)。
     * 仅查已通过且未删的帖子;返回按相关度(默认) + id 倒序。
     */
    @Select("SELECT p.* FROM post p WHERE p.status = 1 AND p.is_deleted = 0 " +
            "AND MATCH(p.content) AGAINST (#{keyword} IN NATURAL LANGUAGE MODE) " +
            "ORDER BY p.id DESC LIMIT #{limit}")
    List<Post> searchByFullText(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * Feed 自定义查询(支持按 like/comment/view 排序 + JOIN 话题过滤)。
     * sql 由 service 层拼接,排序列与话题 id 走白名单/查表校验,用户输入不直接进 sql。
     */
    @Select("${sql}")
    List<Post> selectFeedBySql(@Param("sql") String sql);
}
