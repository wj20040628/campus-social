package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.PostFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface PostFavoriteMapper extends BaseMapper<PostFavorite> {

    /**
     * 取消收藏：物理删除，绕过 {@code @TableLogic} 软删。
     *
     * <p>软删会留下行继续锁住 uk_fav_user_post(user_id, post_id)，
     * 用户取消后再点收藏，INSERT 直接 Duplicate entry。
     * 这与 {@code LikeMapper#hardDelete} 是同一类问题，处理方式保持一致。
     */
    @Delete("DELETE FROM post_favorite WHERE user_id = #{userId} AND post_id = #{postId}")
    int hardDelete(@Param("userId") long userId, @Param("postId") long postId);

    /**
     * 帖子被删除时清理该帖的全部收藏。
     *
     * <p>帖子删了但收藏行还在，会导致「我的收藏」里出现打不开的幽灵条目。
     * 这里连 is_deleted=1 的行一起删，避免残留行占用唯一键。
     */
    @Delete("DELETE FROM post_favorite WHERE post_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /**
     * 批量判定「我对这批帖子收藏了哪些」,返回已收藏的帖子 id。
     *
     * <p>列表页一页 20 条,逐条 selectCount 就是 20 次查询;
     * 一次 IN 查询能全部命中,是 Feed 里唯一被消掉的 N+1。
     * 条件列走 uk_fav_user_post(user_id, post_id),IN 能完整用上索引。
     */
    @Select("<script>"
            + "SELECT post_id FROM post_favorite WHERE user_id = #{userId} AND is_deleted = 0 AND post_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Long> selectFavoritedPostIds(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /**
     * 某人收藏了多少条(个人主页「收藏」Tab 的数字)。
     *
     * <p>为什么不复用 selectList().size():列表接口只返回一页(20 条),
     * 拿页长度当总数会在超过 20 条后永远显示 20。
     */
    @Select("SELECT COUNT(*) FROM post_favorite WHERE user_id = #{userId} AND is_deleted = 0")
    int countByUser(@Param("userId") long userId);
}
