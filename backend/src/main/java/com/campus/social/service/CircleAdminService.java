package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.dto.ReservedNameDTO;
import com.campus.social.vo.CircleMemberVO;
import com.campus.social.vo.ReservedNameVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 圈子「圈管理 / 吧主级权限」域。
 *
 * <p>角色模型(仿贴吧「吧主」):
 * <ul>
 *   <li>圈主(role=1):固定不可转让,天然拥有全部吧主权限。</li>
 *   <li>圈管理(role=2):由圈主任命,拥有吧主级权限(置顶/删帖/移帖/封禁成员),
 *       但<b>不能再任命别人</b>为圈管理(权限止于 role=2,防层层下放)。</li>
 *   <li>成员(role=3):可被圈主/圈管理封禁(软封禁:保留成员行,禁发帖/评论/点赞)。</li>
 * </ul>
 *
 * <p>官方名保护:建圈撞预留名由 {@link CircleService} 拦截;
 * 已被抢注的圈子由本 service 的 {@code transferCircle} / {@code banCircle} 由管理员处置。
 */
public interface CircleAdminService {

    // ---------------- 吧主级:帖子处置(需 role<=2 且圈内,或圈主) ----------------

    /** 置顶帖(同圈仅留 1 个:置 1 时其它置顶归 0)。 */
    void topPost(long postId, long me);

    /** 取消置顶。 */
    void untopPost(long postId, long me);

    /** 下架帖(status=2 软,作者可自查;非物理删)。 */
    void takedownPost(long postId, long me);

    /** 移帖:改归属圈;toCircleId=null 移回广场。 */
    void movePost(long postId, Long toCircleId, long me);

    // ---------------- 吧主级:成员处置 ----------------

    /** 封禁圈内成员(软封禁:保留成员行,置 ban_status=1;until=null 长期)。 */
    void banMember(long circleId, long targetUserId, LocalDateTime until, long me);

    /** 解除成员封禁。 */
    void unbanMember(long circleId, long targetUserId, long me);

    /** 圈主任命圈管理(role 3→2)。仅圈主可(圈管理不能再任命)。 */
    void appointAdmin(long circleId, long targetUserId, long me);

    /** 取消圈管理(role 2→3)。仅圈主可。 */
    void revokeAdmin(long circleId, long targetUserId, long me);

    /** 列圈内成员(供管理面板):role + banStatus + banUntil。 */
    List<CircleMemberVO> members(long circleId, long viewerId);

    /**
     * 管理员处置列表:查全量圈子(含 status=1 封禁/解散的),按 id 倒序游标分页。
     * 区别于 CircleService.list(只查 status=0):被封禁/解散的圈子也要在管理后台可见,
     * 否则管理员封了之后找不到入口解封/恢复。
     * 返回 CircleVO,joined/myRole/myBanned 按 viewerId(admin)回填。
     */
    CursorPage<com.campus.social.vo.CircleVO> listForAdmin(Long lastId, int size, long adminId);

    // ---------------- 管理员:官方名处置 ----------------

    /** 移交圈主(管理员):owner_id 翻转 + 原圈主 1→3、新圈主 role→1。新圈主须为圈内成员。 */
    void transferCircle(long circleId, long newOwnerId, long adminId);

    /** 封禁圈子(circle.status=1,管理员)。 */
    void banCircle(long circleId, long adminId);

    /** 解封圈子(circle.status=0,管理员)。 */
    void unbanCircle(long circleId, long adminId);

    /**
     * 解散圈子(仅圈主 role=1):软删圈子(status=1)+ 级联软删圈内帖子。
     * 成员行不删 —— 保留成员关系,该圈 status=1 后列表自动过滤(见 CircleMapper.list)。
     * 数据保留可审计,管理员需要时可恢复(见 restoreCircle)。
     */
    void disbandCircle(long circleId, long operatorId);

    /**
     * 恢复解散的圈子(管理员兜底,思路 B)。对称 disbandCircle:
     * 圈子 status 1→0 + 圈内帖子 status 2→1。仅管理员可用(requireAdmin),
     * 圈主侧不提供恢复入口 —— 解散文案承诺「不可恢复」,恢复只走平台兜底通道。
     */
    void restoreCircle(long circleId, long adminId);

    // ---------------- 预留名(管理员/运营后台) ----------------

    void addReservedName(ReservedNameDTO dto, long adminId);

    void removeReservedName(String name, long adminId);

    CursorPage<ReservedNameVO> listReservedNames(Long lastId, int size, long adminId);

    /** 建圈时判断某名是否被预留(供 CircleService.createCircle 复用)。 */
    boolean isReservedName(String name);

    /**
     * me 在 circle 是否被圈内封禁(仅 role=3 普通成员,且未到期)。
     * 供发帖/评论/点赞写路径复用拦截(圈主/圈管理恒不拦)。
     */
    boolean isBanned(long circleId, long me);
}
