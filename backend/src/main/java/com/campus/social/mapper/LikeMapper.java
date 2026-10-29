package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Like;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface LikeMapper extends BaseMapper<Like> {

    /** 物理删除某帖子的点赞 */
    @Delete("DELETE FROM `like` WHERE target_type = 1 AND target_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /**
     * 某人赞过多少条帖子(个人主页「赞过」Tab 的数字)。
     *
     * <p>只数 target_type=1(帖子):评论点赞不算「赞过」的内容,
     * 如果把评论点赞也算进去,Tab 上的数字会比列表里能看到的条目还多。
     */
    @Select("SELECT COUNT(*) FROM `like` WHERE user_id = #{userId} AND target_type = 1 AND is_deleted = 0")
    int countLikedPosts(@Param("userId") long userId);

    /** 物理删除某评论的点赞 */
    @Delete("DELETE FROM `like` WHERE target_type = 2 AND target_id = #{commentId}")
    int hardDeleteByComment(@Param("commentId") long commentId);

    /**
     * 物理删除某人对某目标的点赞(取消点赞用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_like(user_id,target_type,target_id) 继续锁住,
     * 导致再点赞 insert 撞 Duplicate entry。取消点赞应彻底删行。
     */
    @Delete("DELETE FROM `like` WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}")
    int hardDelete(@Param("userId") long userId, @Param("targetType") int targetType, @Param("targetId") long targetId);

    @Delete("DELETE FROM `like` WHERE target_type = #{targetType} AND target_id = #{targetId}")
    int hardDeleteByTarget(@Param("targetType") int targetType, @Param("targetId") long targetId);

    /**
     * 批量判定「我对这批评论是否已赞」，返回已赞的评论 id 集合。
     *
     * <p>评论列表一页 20 条时，逐条 selectCount 会打出 20 次 count；
     * 树形组装后条数更多（含全部子孙），逐条查更不可接受。
     *
     * <p>用 {@code target_id IN (...)} 而不是 `LIKE 'prefix'`：
     * 前者能走 {@code idx_user_type(user_id, target_type, target_id)} 全命中，
     * 后者在 id 非等值时只能全表扫。
     */
    @Select("<script>"
            + "SELECT target_id FROM `like` WHERE user_id = #{userId} AND target_type = 2 "
            + "AND target_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Long> selectLikedCommentIds(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /**
     * 批量判定「我对这批帖子是否已赞」，返回已赞的帖子 id 集合。
     *
     * <p><b>为什么必须批量</b>：Feed 一页 20 条帖子，原来的做法是每条帖子一次
     * {@code SELECT COUNT(*)}，也就是一次首屏请求打出 20 次点赞判定查询——
     * 首屏 SQL 里 80% 都是这些。换成一次 IN 查询后整页只剩 1 次。
     *
     * <p>用 {@code target_id IN (...)} 而不是按前缀/范围匹配：
     * 这样能走 uk_like(user_id, target_type, target_id) 做等值命中，
     * 换成范围条件后半段索引失效。
     *
     * <p>带上 {@code is_deleted = 0}：与 countLikedPosts 口径一致，
     * 软删的点赞行不该显示成「已赞」。
     */
    @Select("<script>"
            + "SELECT target_id FROM `like` WHERE user_id = #{userId} AND target_type = 1 "
            + "AND is_deleted = 0 AND target_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Long> selectLikedPostIds(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /**
     * 批量取评论点赞数，返回 target_id → count。
     *
     * <p>与 {@link #selectLikedCommentIds} 拆成两条而不是一条：
     * 「我赞没赞」按 user_id 过滤、「多少人赞」全表统计，
     * 过滤条件不同、走的索引也不同，合成一条会让其中一半用不上索引。
     */
    @Select("<script>"
            + "SELECT target_id, COUNT(*) AS cnt FROM `like` WHERE target_type = 2 "
            + "AND target_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + " GROUP BY target_id"
            + "</script>")
    List<LikeCountRow> countByTargetIds(@Param("ids") Collection<Long> ids);

    /** target_id → 点赞数 行投影 */
    class LikeCountRow {
        private Long targetId;
        private Integer cnt;

        public Long getTargetId() {
            return targetId;
        }

        public void setTargetId(Long targetId) {
            this.targetId = targetId;
        }

        public Integer getCnt() {
            return cnt;
        }

        public void setCnt(Integer cnt) {
            this.cnt = cnt;
        }
    }
}
