package com.campus.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReservedCircleName;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReservedCircleNameMapper;
import com.campus.social.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 圈子「圈管理 / 吧主级权限」+「官方名保护」集成测试(本地 profile,连本机 MySQL+Redis)。
 *
 * <p>覆盖计划 §五 列出的规则:
 * <ul>
 *   <li>topPost 同圈仅 1 个置顶;非 role≤2 抛 403(1018)。</li>
 *   <li>takedown 改 status=2;movePost 改 circle_id。</li>
 *   <li>封禁成员后发帖/评论/点赞被拦;到期自动解除;圈主不被拦。</li>
 *   <li>任命/取消圈管理改 role;圈管理不能再任命(403)。</li>
 *   <li>移交圈主:owner_id + 双方 role 翻转;仅管理员可(非 admin 403)。</li>
 *   <li>建圈撞预留名 → CIRCLE_NAME_RESERVED。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class CircleAdminServiceTest {

    @Autowired private CircleAdminService service;
    @Autowired private CircleService circleService;
    @Autowired private CircleMapper circleMapper;
    @Autowired private CircleMemberMapper memberMapper;
    @Autowired private PostMapper postMapper;
    @Autowired private ReservedCircleNameMapper reservedMapper;
    @Autowired private UserMapper userMapper;

    /** 固定测试 user(与 mkt 测试错开,避免串号) */
    private static final AtomicLong SEQ = new AtomicLong(8_800_000L);
    private long creator;   // 圈主 role=1
    private long admin;     // 圈管理 role=2
    private long member;    // 普通成员 role=3
    private long outsider;   // 非成员
    private long adminUser;  // 平台管理员(role=1 全局)
    private long circleId;
    private long postA, postB;
    private final List<Long> reservedCleanup = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        creator = SEQ.incrementAndGet();
        admin   = SEQ.incrementAndGet();
        member  = SEQ.incrementAndGet();
        outsider= SEQ.incrementAndGet();
        adminUser = 1L; // 假定 id=1 是平台管理员(mkt/verify 测试同款约定,见 RoleService)

        for (long uid : new long[]{creator, admin, member, outsider}) {
            ensureUser(uid, "circle_test_" + uid, "圈测" + uid, 0);
        }

        // 建圈:creator 为圈主
        // 圈名正则限 3-7 字符且必须含汉字(CircleServiceImpl#validateName)。
        // 旧写法 "圈测试"+uid+nanoTime 约 29 字符,必撞 CIRCLE_NAME_INVALID —— 18/18 全错的根因。
        // 现写法:「测」+ 6 位 nanotime 尾数 = 7 字符,含汉字、在长度内、跨运行唯一
        // (uk_name 不含 is_deleted,用固定数字会与上次运行残留的圈撞唯一键)。
        String circleName = "测" + String.format("%06d", System.nanoTime() % 1_000_000);
        long c = createCircleByUser(creator, circleName);
        circleId = c;

        // admin / member / outsider 加入,分别置 role
        joinAs(admin, 2);
        joinAs(member, 3);
        // outsider 故意不加入

        // 造两个通过态帖子(creator 发)
        postA = createPost(creator, circleId, "帖A-" + System.nanoTime());
        postB = createPost(creator, circleId, "帖B-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        try {
            for (long pid : new long[]{postA, postB}) {
                if (pid > 0) postMapper.deleteById(pid);
            }
            if (circleId > 0) {
                // 物理删成员行(绕 @TableLogic),再删圈
                for (long uid : new long[]{creator, admin, member, outsider}) {
                    memberMapper.hardDelete(circleId, uid, 0);
                }
                circleMapper.deleteById(circleId);
            }
            for (Long rid : reservedCleanup) {
                reservedMapper.deleteById(rid);
            }
            reservedCleanup.clear();
        } catch (Exception ignore) {
        }
    }

    /* ---------------- 造数辅助 ---------------- */

    private void ensureUser(long uid, String username, String nickname, int role) {
        User u = userMapper.selectById(uid);
        if (u != null) return;
        u = new User();
        u.setId(uid);
        u.setUsername(username);
        u.setNickname(nickname);
        u.setRole(role);
        u.setPasswordHash("$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"); // NOT NULL 兜底,测试从不登录
        userMapper.insert(u);
    }

    private long createCircleByUser(long owner, String name) {
        com.campus.social.dto.CircleDTO dto = new com.campus.social.dto.CircleDTO();
        dto.setName(name);
        return circleService.createCircle(owner, dto);
    }

    /** 把 uid 加进 circle,并置指定 role(1圈主/2圈管理/3成员)。 */
    private void joinAs(long uid, int role) {
        CircleMember cm = new CircleMember();
        cm.setCircleId(circleId);
        cm.setUserId(uid);
        cm.setRole(role);
        memberMapper.insert(cm);
        circleMapper.adjustMemberCount(circleId, 1);
    }

    private long createPost(long author, Long circleId, String content) {
        Post p = new Post();
        p.setAuthorId(author);
        p.setContent(content);
        p.setCircleId(circleId);
        p.setStatus(1); // 通过
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setHotScore(0.0);
        postMapper.insert(p);
        return p.getId();
    }

    private Post reloadPost(long pid) {
        return postMapper.selectById(pid);
    }

    /* ---------------- 吧主级:帖子处置 ---------------- */

    @Test
    void topPost_adminCanTopAndOnlyOnePinPerCircle() {
        service.topPost(postA, admin); // 圈管理可置顶
        Post a = reloadPost(postA);
        assertEquals(Integer.valueOf(1), a.getIsTop(), "置顶后 is_top=1");

        service.topPost(postB, creator); // 再置顶另一帖
        Post b = reloadPost(postB);
        Post a2 = reloadPost(postA);
        assertEquals(Integer.valueOf(1), b.getIsTop(), "新置顶 is_top=1");
        assertEquals(Integer.valueOf(0), a2.getIsTop(), "旧置顶归 0(同圈仅 1 个)");
    }

    @Test
    void topPost_memberWithoutRoleRejected() {
        // outsider 非成员:NOT_CIRCLE_OWNER(1018)
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.topPost(postA, outsider));
        assertEquals(ResultCode.NOT_CIRCLE_OWNER.getCode(), ex.getCode());
    }

    @Test
    void topPost_plainMemberRejected() {
        // role=3 普通成员:非吧主级
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.topPost(postA, member));
        assertEquals(ResultCode.NOT_CIRCLE_OWNER.getCode(), ex.getCode());
    }

    @Test
    void takedown_setsStatus2SoftDelete() {
        service.takedownPost(postA, creator);
        Post p = reloadPost(postA);
        assertEquals(Integer.valueOf(2), p.getStatus(), "下架 status=2(软)");
        // 记录仍在(不物理删)
        assertNotNull(p);
    }

    @Test
    void movePost_toSquare_setsCircleIdNull() {
        service.movePost(postA, null, creator);
        Post p = reloadPost(postA);
        assertNull(p.getCircleId(), "移到广场 circle_id=NULL");
    }

    @Test
    void movePost_toOtherCircle_setsTargetCircleId() {
        // 目标圈名同样受 3-7 字符 + 含汉字约束
        long otherCircle = createCircleByUser(admin, "标" + String.format("%06d", System.nanoTime() % 1_000_000));
        joinAsClean(otherCircle, creator, 3);
        try {
            /*
             * 2026-10-23 语义变更(审查 P0-3):移入目标圈需要**目标圈管理权**,
             * 只校验源圈等于「A 圈主可把帖子塞进 B 圈」。
             * 这里由 admin 操作:他是新圈圈主(目标圈权限 ✓),也是主圈圈管理(源圈权限 ✓)。
             */
            service.movePost(postA, otherCircle, admin);
            Post p = reloadPost(postA);
            assertEquals(otherCircle, p.getCircleId(), "移到目标圈");
        } finally {
            // 清理目标圈
            memberMapper.hardDelete(otherCircle, creator, 0);
            memberMapper.hardDelete(otherCircle, admin, 0);
            circleMapper.deleteById(otherCircle);
        }
    }

    @Test
    void movePost_toOtherCircleWithoutTargetAdmin_rejected() {
        /*
         * 负向用例(审查 P0-3 的核心):creator 只是源圈圈主,
         * 对 admin 的圈没有任何权限 —— 不能把帖子塞进去。
         * 修复前这条会成功(只校验源圈),等于任意圈主污染别人的圈。
         */
        long otherCircle = createCircleByUser(admin, "标" + String.format("%06d", System.nanoTime() % 1_000_000));
        joinAsClean(otherCircle, creator, 3); // creator 在该圈只是普通成员
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.movePost(postA, otherCircle, creator));
            assertEquals(ResultCode.NOT_CIRCLE_OWNER.getCode(), ex.getCode(),
                    "非目标圈管理不能把帖子移入他人圈子");
        } finally {
            memberMapper.hardDelete(otherCircle, creator, 0);
            circleMapper.deleteById(otherCircle);
        }
    }

    /** 给「别的圈」加成员,不影响主测试圈成员数。 */
    private void joinAsClean(long targetCircle, long uid, int role) {
        CircleMember cm = new CircleMember();
        cm.setCircleId(targetCircle);
        cm.setUserId(uid);
        cm.setRole(role);
        memberMapper.insert(cm);
    }

    /* ---------------- 吧主级:成员处置 ---------------- */

    @Test
    void banMember_blocksWritePaths_untilExpiry() {
        // 圈主封禁普通成员(长期)
        service.banMember(circleId, member, null, creator);
        assertTrue(service.isBanned(circleId, member), "封禁后 isBanned=true");

        // 到期自动解除(不落库):ban_until 已过 → isBanned=false
        memberMapper.setBan(circleId, member, 1, LocalDateTime.now().minusMinutes(1));
        assertFalse(service.isBanned(circleId, member), "到点后视为自动解除");

        // 解禁:ban_status=0
        memberMapper.setBan(circleId, member, 0, null);
        assertFalse(service.isBanned(circleId, member));
    }

    @Test
    void banMember_creatorAndAdminImmune() {
        // 即便把圈主/圈管理的 ban_status 误标 1,写路径也不拦(role≠3)
        memberMapper.setBan(circleId, admin, 1, null);
        assertFalse(service.isBanned(circleId, admin), "圈管理不受封禁拦");
        memberMapper.setBan(circleId, creator, 1, null);
        assertFalse(service.isBanned(circleId, creator), "圈主不受封禁拦");
        // 复位
        memberMapper.setBan(circleId, admin, 0, null);
        memberMapper.setBan(circleId, creator, 0, null);
    }

    @Test
    void banMember_cannotBanSelfOrCreator() {
        // 封禁圈主:target role=1,被拦
        assertThrows(BusinessException.class, () -> service.banMember(circleId, creator, null, creator));
        // 圈主封禁自己
        assertThrows(BusinessException.class, () -> service.banMember(circleId, creator, null, creator));
    }

    @Test
    void appointAdmin_onlyCreatorCanAppoint() {
        // 圈主任命 member→圈管理
        service.appointAdmin(circleId, member, creator);
        CircleMember m = memberRow(circleId, member);
        assertEquals(Integer.valueOf(2), m.getRole(), "任命后 role=2");

        // 圈管理不能再任命别人(无任命权)
        assertThrows(BusinessException.class,
                () -> service.appointAdmin(circleId, outsider, admin));
    }

    @Test
    void revokeAdmin_dropsAdminToMember() {
        service.appointAdmin(circleId, member, creator);
        service.revokeAdmin(circleId, member, creator);
        CircleMember m = memberRow(circleId, member);
        assertEquals(Integer.valueOf(3), m.getRole(), "取消后 role=3");
    }

    private CircleMember memberRow(long cid, long uid) {
        return memberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, cid)
                .eq(CircleMember::getUserId, uid));
    }

    /* ---------------- 管理员:官方名处置 ---------------- */

    @Test
    void transferCircle_flipsOwnerAndRoles() {
        // admin(圈内成员)被移交为圈主;原 creator 降为成员
        service.transferCircle(circleId, admin, adminUser);
        Circle c = circleMapper.selectById(circleId);
        assertEquals(admin, c.getOwnerId(), "owner_id 翻转为新圈主");
        assertEquals(Integer.valueOf(1), memberRow(circleId, admin).getRole(), "新圈主 role=1");
        assertEquals(Integer.valueOf(3), memberRow(circleId, creator).getRole(), "原圈主降 role=3");
    }

    @Test
    void transferCircle_nonAdminRejected() {
        // 非管理员(圈内成员)不能移交
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferCircle(circleId, admin, creator));
        // NOT_ADMIN(1025)
        assertEquals(ResultCode.NOT_ADMIN.getCode(), ex.getCode());
    }

    @Test
    void transferCircle_newOwnerMustBeMember() {
        // outsider 非圈内成员:不能成为新圈主
        assertThrows(BusinessException.class,
                () -> service.transferCircle(circleId, outsider, adminUser));
    }

    @Test
    void banCircle_adminSetsStatus1() {
        service.banCircle(circleId, adminUser);
        Circle c = circleMapper.selectById(circleId);
        assertEquals(Integer.valueOf(1), c.getStatus(), "封禁 status=1");
        service.unbanCircle(circleId, adminUser);
        c = circleMapper.selectById(circleId);
        assertEquals(Integer.valueOf(0), c.getStatus(), "解封 status=0");
    }

    /* ---------------- 官方名保护:建圈拦截 ---------------- */

    @Test
    void createCircle_hitsReservedName_rejected() {
        // 预留名
        ReservedCircleName rn = new ReservedCircleName();
        // 预留名必须也能通过建圈的长度正则(3-7 字符 + 含汉字),否则建圈先撞 1056 长度错误,
// 根本走不到「撞预留名」那段 —— 这正是该用例原先红的原因。
        rn.setName("预留" + String.format("%04d", System.nanoTime() % 10_000));
        rn.setNote("test");
        reservedMapper.insert(rn);
        reservedCleanup.add(rn.getId());

        com.campus.social.dto.CircleDTO dto = new com.campus.social.dto.CircleDTO();
        dto.setName(rn.getName());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> circleService.createCircle(creator, dto));
        assertEquals(ResultCode.CIRCLE_NAME_RESERVED.getCode(), ex.getCode());
    }

    @Test
    void reservedName_addRemoveList() {
        String nm = "预名" + System.nanoTime();
        com.campus.social.dto.ReservedNameDTO add = new com.campus.social.dto.ReservedNameDTO();
        add.setName(nm);
        add.setNote("op-note");
        service.addReservedName(add, adminUser);
        assertTrue(service.isReservedName(nm), "加入后 isReservedName=true");

        var page = service.listReservedNames(null, 50, adminUser);
        boolean found = page.getList().stream().anyMatch(r -> nm.equals(r.getName()));
        assertTrue(found, "列表能查到");

        service.removeReservedName(nm, adminUser);
        assertFalse(service.isReservedName(nm), "移除后 isReservedName=false");
        // 清 reservedCleanup(移除已删库行,reservedCleanup 里的 id 可能已不存在,deleteById 幂等无害)
        reservedCleanup.clear();
    }

    @Test
    void reservedName_addNonAdminRejected() {
        com.campus.social.dto.ReservedNameDTO add = new com.campus.social.dto.ReservedNameDTO();
        add.setName("x" + System.nanoTime());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addReservedName(add, creator));
        assertEquals(ResultCode.NOT_ADMIN.getCode(), ex.getCode());
    }
}
