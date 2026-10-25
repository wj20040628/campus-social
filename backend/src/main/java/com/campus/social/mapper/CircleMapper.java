package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Circle;
import com.campus.social.vo.RecommendCircleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CircleMapper extends BaseMapper<Circle> {

    @Update("UPDATE circle SET member_count = member_count + #{delta}, update_time = NOW() WHERE id = #{circleId}")
    int adjustMemberCount(@Param("circleId") Long circleId, @Param("delta") int delta);

    /** 圈主移交(管理员):owner_id 翻转,成员行角色由 service 同步翻转 */
    @Update("UPDATE circle SET owner_id = #{to}, update_time = NOW() WHERE id = #{circleId} AND is_deleted = 0")
    int transferOwner(@Param("circleId") long circleId, @Param("to") long to);

    /** 封禁/解封圈子(status 1↔0) */
    @Update("UPDATE circle SET status = #{status}, update_time = NOW() WHERE id = #{circleId} AND is_deleted = 0")
    int setStatus(@Param("circleId") long circleId, @Param("status") int status);

    /**
     * 解散圈子(圈主级):status=1(软删)。
     * 与 banCircle 同机制但调用方不同 —— banCircle 走 roleService.requireAdmin(平台管理员),
     * 本方法由 CircleAdminServiceImpl.disband 走 requireCircleCreator(仅圈主),
     * 共用同一物理开关。
     */
    @Update("UPDATE circle SET status = 1, update_time = NOW() WHERE id = #{circleId} AND is_deleted = 0")
    int disband(@Param("circleId") long circleId);

    /** 对账:重算 member_count */
    @Update("UPDATE circle c SET " +
            "  member_count = (SELECT COUNT(*) FROM circle_member cm WHERE cm.circle_id = c.id AND cm.is_deleted = 0), " +
            "  update_time = NOW()")
    int reconcileMemberCount();

    /**
     * 引导推荐圈子（ARCHITECTURE §3 Q10.4）。
     *
     * <p>排序 {@code member_count DESC, create_time DESC, id DESC}：
     * 末位 id DESC 是必需的——同一批种子圈子的 create_time 可能完全相同
     * （批量导入时秒级精度会撞），没有末位兜底则每次刷新顺序都可能不同。
     *
     * <p>排除已加入：{@code circle_member.is_deleted = 0} 必须带。
     * 漏掉它会让「退圈」的用户再也看不到这个圈子——用户会以为圈子消失了。
     *
     * <p>排除 status=1（封禁）的圈子：封禁意味着不让出现在任何列表里。
     */
    @Select("SELECT c.id, c.name, c.member_count, c.create_time FROM circle c "
            + "WHERE c.is_deleted = 0 AND c.status = 0 "
            + "  AND NOT EXISTS (SELECT 1 FROM circle_member cm "
            + "                  WHERE cm.circle_id = c.id AND cm.user_id = #{me} AND cm.is_deleted = 0) "
            + "ORDER BY c.member_count DESC, c.create_time DESC, c.id DESC "
            + "LIMIT #{limit}")
    List<RecommendCircleRow> selectRecommendCircles(@Param("me") Long me, @Param("limit") int limit);

    /**
     * 某用户名下**在世**圈子数(2026-10-25,建圈配额用)。
     * 在世 = status=0 且未删:解散/被封禁的释放名额——被封圈的圈主
     * 已承受圈被下架的处罚,配额不放行等于二次永久惩罚。
     */
    @Select("SELECT COUNT(*) FROM circle WHERE owner_id = #{ownerId} AND status = 0 AND is_deleted = 0")
    int countActiveOwnedBy(@Param("ownerId") Long ownerId);
}
