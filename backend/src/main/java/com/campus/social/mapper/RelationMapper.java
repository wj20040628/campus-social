package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Relation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface RelationMapper extends BaseMapper<Relation> {

    /**
     * 物理删除某条关系(取消关注/好友/拉黑用)。
     * 绕过 @TableLogic 软删:软删留行会让 uk_rel(from_user_id,to_user_id,type) 继续锁住,
     * 再次 follow/加友/拉黑 时 insert 撞 Duplicate entry。取消关系应彻底删行。
     */
    @Delete("DELETE FROM relation WHERE from_user_id = #{fromUserId} AND to_user_id = #{toUserId} AND type = #{type}")
    int hardDelete(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId, @Param("type") int type);

    /**
     * 双向黑名单判定（F3 护栏第3 道）。
     *
     * <p>两个方向都要查：拉黑是单向动作，A 拉黑 B 后 B 仍可向A 发消息，
     * 若只查 A→B，B 就能绕过。只查单向等于没护栏。
     *
     * <p>顺带把「是否已是好友」一起带出（同一条关系表，省一次查询）：
     * 返回行存在即说明 me→peer 或 peer→me 至少有一条非黑名单关系。
     */
    @Select("SELECT COUNT(*) FROM relation "
            + "WHERE is_deleted = 0 AND type = 3 "
            + "  AND ((from_user_id = #{me} AND to_user_id = #{peer}) "
            + "    OR (from_user_id = #{peer} AND to_user_id = #{me}))")
    int countBlacklistEitherDirection(@Param("me") long me, @Param("peer") long peer);

    /**
     * 我是否拉黑了任何人/被任何人拉黑(2026-10-24 加,Feed 缓存旁路判定)。
     * 只判存在性:有任一拉黑关系的用户不走共享首屏缓存与快速路径,
     * 改走带 NOT EXISTS 过滤的 SQL(见 PostFeedQueryService#feed)。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM relation "
            + "WHERE is_deleted = 0 AND type = 3 "
            + "  AND (from_user_id = #{me} OR to_user_id = #{me}))")
    boolean existsAnyBlacklist(@Param("me") long me);

    /**
     * 我与 peer 之间是否存在「关注或好友」关系（任一方向）。
     *
     * <p>F3 判定 isStranger 用：不存在即视为陌生人，走配额扣减。
     * 关注是单向的，所以两个方向都要算。
     */
    @Select("SELECT COUNT(*) FROM relation "
            + "WHERE is_deleted = 0 AND type IN (1, 2) "
            + "  AND ((from_user_id = #{me} AND to_user_id = #{peer}) "
            + "    OR (from_user_id = #{peer} AND to_user_id = #{me}))")
    int countFollowOrFriendEitherDirection(@Param("me") long me, @Param("peer") long peer);

    /**
     * 批量取我已关注的 userId 子集（F11 引导批量关注用）。
     *
     * <p>为什么必须批量：引导页允许一次勾选 50 人。
     * 若逐个调 {@code RelationService.status()}，每次内部要打 4 条 SQL
     * （following/followedBy/friend/blacklisted），50 人就是 200 条查询——
     * 一次引导请求把连接池打满，p95 直接崩。这里一条 IN 查询解决。
     *
     * <p>返回 Set 而非 List：调用方只做 contains 判断，
     * List.contains 是 O(n)，50 个元素时每个目标都要扫一遍，白白放大。
     */
    @Select("<script>"
            + "SELECT to_user_id FROM relation "
            + "WHERE from_user_id = #{me} AND type = 1 AND is_deleted = 0 AND to_user_id IN "
            + "<foreach collection='userIds' item='u' open='(' separator=',' close=')'>#{u}</foreach>"
            + "</script>")
    Set<Long> selectFollowedTargets(@Param("me") long me, @Param("userIds") List<Long> userIds);
}
