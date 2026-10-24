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
    /**
     * 清理用。测试要绕过 @TableLogic 做物理删除，而 Mapper 上没有对应的
     * hardDelete，为此在生产 Mapper 里加方法不值得 —— 直接走原生 SQL。
     */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 固定测试 user(与 mkt 测试错开,避免串号) */
    private static final AtomicLong SEQ = new AtomicLong(8_800_000L);

    /**
     * 本类专用的平台管理员 id。
     * 避开 SEQ 的 8_800_000+ 与 MktActivityServiceTest 的 9_900_001+，互不干扰。
     * 不用 1L —— 那个 id 在本机是真实账号、在 CI 空库里不存在，两边行为不一致。
     */
    private static final long ADMIN_UID = 9_800_001L;
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
        adminUser = ADMIN_UID;
        /*
         * 平台管理员必须**真实存在**：RoleServiceImpl.isAdmin 是查库判 role==1，
         * 不是拿 id 猜的。
         *
         * 这里原先写死 adminUser = 1L 并注释「假定 id=1 是平台管理员」，但没人创建它。
         * 本地恰好有 id=1 的真实账号所以一直通过；CI 用 schema.sql 建的是**全新空库**
         * （该文件不含任何 INSERT，user 表是空的），于是三个走管理员权限的用例
         * 全部抛「需要管理员权限」—— 这正是 CI 后端 job 一直红、本地却永远复现不出来的原因。
         *
         * 两个刻意的选择：
         *  1) 用一个**独立于其他测试**的 id（既不与 SEQ 的 8_800_000+ 重叠，
         *     也不与 MktActivityServiceTest 的 9_900_001+ 重叠），互不干扰。
         *  2) 用 ON DUPLICATE KEY UPDATE 做成**幂等**。不能走 ensureUser 的
         *     「先 selectById 再 insert」：那张表在空库上第一次会插入 id=1，
         *     而 id=1 在本机是真实账号、在 CI 是空 —— 两条路径行为不一致。
         *     幂等 upsert 在两种环境下都只留一行，也不会覆盖本地真实账号
         *     （本用例完全不碰 id=1）。
         */
        jdbc.update(
                "INSERT INTO user (id, username, nickname, role, password_hash, status, is_deleted,"
                        + " create_time, update_time) "
                        + "VALUES (?, ?, ?, 1, ?, 0, 0, NOW(), NOW()) "
                        + "ON DUPLICATE KEY UPDATE role = 1, is_deleted = 0",
                adminUser, "circle_test_admin", "平台管理员",
                "$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

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
            /*
             * 清理必须全部走【物理删除】（2026-10-23 修）。
             *
             * 原来的三个问题叠加，导致每跑一次就永久留下一批数据：
             *
             * 1) memberMapper.hardDelete(circleId, uid, 0) —— 那条 SQL 是
             *    `WHERE circle_id=? AND user_id=? AND role=?`，而本测试里成员角色是
             *    1/2/3，传 0 一条都匹配不上 → 成员行从未被删过。
             * 2) circleMapper.deleteById(circleId) 走的是 @TableLogic 软删，
             *    而 circle.uk_name 是建在 name 上的**不含 is_deleted**，
             *    所以软删后这个名字永久不可复用 —— 这正是「圈名随机 6 位数仍会
             *    撞 DuplicateKey」的根因：库里的测试圈越积越多，可用名字越来越少。
             * 3) catch (Exception ignore) 把失败吞得干干净净，没人知道清理没生效。
             *
             * 实测后果：本地库里积了 180 个测试圈 / 525 行成员，直到某次跑测试
             * 撞上 uk_name 才暴露，表现为随机某个用例偶发 DuplicateKey。
             */
            if (postA > 0 || postB > 0) {
                // 帖子及其衍生行：同样物理删，避免 feed_item/comment 长期堆积
                for (long pid : new long[]{postA, postB}) {
                    if (pid <= 0) continue;
                    jdbc.update("DELETE FROM comment WHERE post_id = ?", pid);
                    jdbc.update("DELETE FROM post_topic WHERE post_id = ?", pid);
                    jdbc.update("DELETE FROM post_favorite WHERE post_id = ?", pid);
                    jdbc.update("DELETE FROM feed_item WHERE post_id = ?", pid);
                    jdbc.update("DELETE FROM post WHERE id = ?", pid);
                }
            }
            if (circleId > 0) {
                jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", circleId);
                jdbc.update("DELETE FROM circle WHERE id = ?", circleId);
            }
            /*
             * 测试用户也要删（2026-10-24 补）。
             *
             * setUp 里 creator/admin/member/outsider 取自 SEQ.incrementAndGet()，
             * 每个用例都是**全新的 4 个 id** —— 于是 19 个用例跑一次就留 76 个用户，
             * 而且从来没人删。实测一次运行就把清空的库从 2 个用户涨到 81 个。
             * 这些用户 id 很大、不撞唯一键，所以一直没暴露成失败，只是纯垃圾。
             * 平台管理员（固定 id）同样在这里删掉，下次 setUp 会幂等重建。
             */
            for (long uid : new long[]{creator, admin, member, outsider, adminUser}) {
                if (uid <= 0) continue;
                jdbc.update("DELETE FROM user_tag WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM upload_file WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", uid, uid);
                jdbc.update("DELETE FROM circle_member WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM user WHERE id = ?", uid);
            }
            /*
             * 预留名同样必须物理删除。
             *
             * ReservedCircleName 带 @TableLogic，reservedMapper.deleteById 是**软删**，
             * 而 reserved_circle_name.uk_rname 建在纯 name 上（不含 is_deleted）——
             * 软删的行仍然占着那个名字，于是每跑一次就永久锁掉一个预留名。
             * 用例里用的是「预留 + 4 位随机数」，只有 1 万种取值，
             * 累积几十条之后就必然撞键：表现为**随机某个用例偶发**
             * DuplicateKey，且每次失败的用例都不一样（本地实测：
             * 一次是 banMember_...，清库后换成 createCircle_hitsReservedName_rejected）。
             */
            for (Long rid : reservedCleanup) {
                jdbc.update("DELETE FROM reserved_circle_name WHERE id = ?", rid);
            }
            reservedCleanup.clear();
        } catch (Exception e) {
            /*
             * 清理失败不该让用例失败（它不影响被测逻辑），但也绝不能静默 ——
             * 静默正是上面那堆垃圾数据积累至今的原因。打到 stderr，
             * surefire 报告里能看到。
             */
            System.err.println("[CircleAdminServiceTest] 清理残留数据失败，"
                    + "会在库里留下测试圈/成员，累计后会导致 uk_name 冲突: " + e.getMessage());
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
            /*
             * 清理目标圈必须物理删除（2026-10-23 修）。
             * 原来的 hardDelete(otherCircle, uid, 0) 里 role 传 0，而那条 SQL 要
             * role 精确匹配（实际是 1/2/3），一条都删不掉；
             * 再配 deleteById 的软删，circle.uk_name 不含 is_deleted，
             * 于是每跑一次这个用例就永久锁死一个「标XXXXXX」圈名。
             */
            jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", otherCircle);
            jdbc.update("DELETE FROM circle WHERE id = ?", otherCircle);
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
            // 同 movePost_toOtherCircle_setsTargetCircleId：必须物理删，否则圈名被永久锁死
            jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", otherCircle);
            jdbc.update("DELETE FROM circle WHERE id = ?", otherCircle);
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
        rn.setName("预留" + String.format("%05d", System.nanoTime() % 100_000));
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
        /*
         * removeReservedName 走的是 @TableLogic 软删，行仍留在库里；
         * 而 reserved_circle_name.uk_rname 建在纯 name 上（不含 is_deleted），
         * 这条软删行会永久占住该名字。本用例用完整 nanoTime，名字唯一、
         * 不会撞键，但每跑一次就积一行，所以这里显式物理删掉。
         *
         * 原来只写 reservedCleanup.clear()，等于把它交给 tearDown 的软删、
         * 再把待清理列表清空 —— 只积累不回收。
         */
        jdbc.update("DELETE FROM reserved_circle_name WHERE name = ?", nm);
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
