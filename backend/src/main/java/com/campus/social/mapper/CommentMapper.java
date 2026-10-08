package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Comment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /** 物理删除某帖子的所有评论 */
    @Delete("DELETE FROM `comment` WHERE post_id = #{postId}")
    int hardDeleteByPost(@Param("postId") long postId);

    /**
     * 一次性取某帖的二级与三级回复（组装一级评论的 children 用）。
     *
     * <p><b>为什么要一条 SQL 取全部而不在 Java 里逐条取子评论</b>：
     * 一页 20 条一级评论，若每条再查一次子评论就是 21 次查询；
     * 更糟的是「每条一级评论的子评论数不同」会导致查询次数随数据波动，
     * 无法用固定条数覆盖。
     *
     * <p><b>为什么带 level IN (2,3) 而不是全帖</b>：一级评论已由列表查询取回，
     * 再查一遍是纯粹的浪费。契约规定三级封顶，所以层级上界是 3，
     * 不需要为「可能有四级」留余地——真出现四级说明写入侧校验漏了。
     *
     * <p>排序 {@code level ASC, id ASC}：先二级后三级，让 Java 侧按顺序
     * 一遍遍历就能建树，不需要递归找父节点（后出现的节点不可能是前面的父节点）。
     * 每层内部按 id 升序 = 取最老的，符合契约「回复链连续可读」的要求。
     *
     * <p><b>注意 {@code parent_id} 的口径</b>：按 openapi 契约，
     * 三级的 parentId 填「其一级祖先的 id」而非直接父级。
     * 因此二级与三级在 {@code idx_post_parent} 里是平级，
     * 建树时必须靠 {@code level} 区分归属，不能只看 parentId。
     */
    @Select("SELECT * FROM `comment` WHERE post_id = #{postId} "
            + "AND status = 1 AND level IN (2, 3) "
            + "ORDER BY level ASC, id ASC")
    List<Comment> selectRepliesOfPost(@Param("postId") long postId);

    /**
     * 溢出补齐用：某条评论的直接子回复，按 id 升序（新的在后），带 id 游标。
     *
     * <p><b>排序只用 id，不用 level</b>：游标是单字段 id，
     * 排序键必须与游标键同向，否则翻页会漏行——
     * {@code ORDER BY level, id} 下三级(id=9)排在二级(id=10)之后，
     * 游标推进到 10 之后 {@code id > 10} 会把 9 整条漏掉，
     * 表现为「总共 3 条，第2 页却是空的」。
     *
     * <p><b>为什么取 id 升序而不是倒序</b>：children 超上限时被截掉的是
     * 「最老的那批」，补齐时应当优先补最新的，否则等于把用户刚看过的那几条
     * 又给一遍、真正的溢出内容永远补不上。
     *
     * <p>用 {@code id > lastId} 而不是 OFFSET：OFFSET 在翻到后段时会退化成全表扫，
     * 且并发插入会导致同一条记录在两页里各出现一次。
     */
    @Select("<script>"
            + "SELECT * FROM `comment` WHERE parent_id = #{parentId} AND status = 1 "
            + "AND level > 1 "
            + "<if test='lastId != null'> AND id > #{lastId} </if>"
            + "ORDER BY id ASC LIMIT #{limit}"
            + "</script>")
    List<Comment> selectChildrenForPage(@Param("parentId") long parentId,
            @Param("lastId") Long lastId, @Param("limit") int limit);

    /** 某条评论的直接子回复总数（算溢出条数用，避免把全部行拉进内存） */
    @Select("SELECT COUNT(*) FROM `comment` WHERE parent_id = #{parentId} AND status = 1")
    int countChildren(@Param("parentId") long parentId);
}