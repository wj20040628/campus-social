package com.campus.social.service;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.dto.MktReactivDTO;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.MktComment;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.MktCommentMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校园限时活动集市 服务端业务规则集成测试（local + test profile,连本机 MySQL + Redis）。
 * 前置:本机 MySQL(测试库 campus_social_test)与 Redis(6379)须已启动。
 * 覆盖计划 §验证 列出的全部规则:
 *   - create 校验:标题 15–25 / 描述 1–500 / 时长 12h 上限(防篡改二次校验) / 敏感词拒收 / 分类 9 选 1
 *   - status 4 态优先级:满员 > 暂停 > 过期 > 投放中
 *   - reactivate:刷新 last_launch_time + 24h/3 次时间窗(Redis 滑动窗口)+ newExpire 越界 400 + 限流 429
 *   - 属主越权 403;软删详情 404
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class MktActivityServiceTest {

    @Autowired private MktActivityService service;
    @Autowired private MktActivityMapper mktMapper;
    @Autowired private MktCommentMapper mktCommentMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private StringRedisTemplate redis;
    /** 清理用：绕过 @TableLogic 做物理删除 */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 配额依赖隔离(2026-10-25):本类关注集市业务规则,在投配额已有
     * CreditQuotaGuardTest 专门覆盖。测试用户 9900001 不存在于 user 表,
     * 真实 trustService 会按"未认证"给配额 0,把全部 create 用例拦下。
     */
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.campus.social.service.impl.ReviewTrustService trustService;

    @org.junit.jupiter.api.BeforeEach
    void stubQuota() {
        org.mockito.Mockito.when(trustService.creationQuota(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(3);
        // 2026-10-30:create 先过行为门槛（账号年龄+信誉分），trustService 被 mock
        org.mockito.Mockito.when(trustService.creditScore(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(60);
        ensureUser(OWNER);
        ensureUser(OTHER);
    }

    /** 造数用的固定 user_id(清理时一起删,避免污染真实数据) */
    private final long OWNER = 9_900_001L;
    private final long OTHER = 9_900_002L;

    private long createdId;
    private final List<Long> myCommentIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        try {
            /*
             * 必须**物理删**（2026-10-24 修）。
             *
             * mktCommentMapper/mktMapper.deleteById 都走 @TableLogic 软删，
             * 行会留在表里且不再被读到 —— 每跑一次就积一批。
             * 实测本地库积了 297 条（全属测试账号 9900001/9900002），
             * 把 mkt_activity 撑到 310 行，而真实数据只有 13 行。
             * 这类垃圾不报错，只让库越来越难看清。
             */
            for (Long cid : myCommentIds) {
                jdbc.update("DELETE FROM mkt_comment WHERE id = ?", cid);
            }
            myCommentIds.clear();
            if (createdId > 0) {
                // 先删挂在其下的留言，再删本体，避免留下孤儿
                jdbc.update("DELETE FROM mkt_comment WHERE activity_id = ?", createdId);
                jdbc.update("DELETE FROM mkt_activity WHERE id = ?", createdId);
                createdId = 0;
            }
            // 清掉限流窗口键,避免下次跑测试被 24h/3 次卡住
            clearMktRedisKeys();
            /*
             * 连测试账号一起删。OWNER/OTHER 是固定 id，下次运行会幂等重建，
             * 所以这里删掉不会影响用例；不删的话跑完测试库里就总会多出这两个用户，
             * 与「只保留真实账号」的意愿相悖。
             */
            for (long uid : new long[]{OWNER, OTHER}) {
                jdbc.update("DELETE FROM user_tag WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM upload_file WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", uid, uid);
                jdbc.update("DELETE FROM user WHERE id = ?", uid);
            }
        } catch (Exception e) {
            // 不静默：清理失败会在库里留垃圾，必须看得见
            System.err.println("[MktActivityServiceTest] 清理失败: " + e.getMessage());
        }
    }

    private void clearMktRedisKeys() {
        for (String key : List.of(
                "mkt:publish:" + OWNER, "mkt:publish:" + OTHER,
                "mkt:comment:" + OWNER, "mkt:comment:" + OTHER,
                "mkt:reactivate:" + createdId)) {
            try {
                redis.delete(key);
            } catch (Exception ignore) {
            }
        }
    }

    /* ---------------- 造数辅助 ---------------- */

    /** 一个合法的活动 DTO(标题恰好 15–25 字,时长 6h 内)。 */
    private MktActivityDTO validDto() {
        MktActivityDTO dto = new MktActivityDTO();
        dto.setCategory("运动约伴");
        dto.setTitle("周末打篮球找搭子一起约球"); // 13 字? 下面校验
        dto.setContent("周六上午学校操场,AA 买水,带上球鞋就行。");
        dto.setTargetNum(4);
        dto.setPrice(BigDecimal.ZERO);
        dto.setLocation("学校操场");
        dto.setExpireTime(LocalDateTime.now().plusHours(6));
        return dto;
    }

    /** 把标题补到合法 15–25 字符区间(中文逐字计数)。 */
    private MktActivityDTO fixTitle() {
        MktActivityDTO dto = validDto();
        dto.setTitle("周六上午篮球场找三个搭子一起约球玩"); // 17 字
        return dto;
    }

    private User ensureUser(long uid) {
        User u = userMapper.selectById(uid);
        if (u != null) return u;
        u = new User();
        u.setId(uid);
        u.setUsername("mkt_test_" + uid);
        u.setNickname("集市测试" + uid);
        u.setRole(0);
        u.setPasswordHash("x");
        userMapper.insert(u);
        // 注册满 4 天：通过发布集市的行为门槛
        jdbc.update("UPDATE user SET create_time = DATE_SUB(NOW(), INTERVAL 4 DAY) WHERE id = ?", uid);
        return u;
    }

    /* ---------------- create 校验 ---------------- */

    @Test
    void create_derivesMainTextFromCategoryAndGame() {
        MktActivityDTO dto = fixTitle();
        // 前端不再采集独立标题,主文案 = 「分类 · 游戏」
        dto.setCategory("游戏组队");
        dto.setSubTag("王者荣耀");
        Long id = service.create(OWNER, dto);
        // 登记待清理。漏了这两处（本用例与 create_acceptsOmittedTitle）时，
        // 每跑一次测试就会在 mkt_activity 留下一条永远不删的记录 —— 实测泄漏 2 条/次。
        createdId = id;
        MktActivityVO vo = service.detail(id, OTHER);
        assertEquals("游戏组队 · 王者荣耀", vo.getMainText());
    }

    @Test
    void create_acceptsOmittedTitle() {
        MktActivityDTO dto = fixTitle();
        dto.setTitle(null); // 标题已改可选,缺省用「分类 · 游戏」当主文案
        Long id = service.create(OWNER, dto);
        createdId = id; // 同上：不登记就会泄漏
        assertNotNull(id);
    }

    @Test
    void create_rejectsContentOver500() {
        MktActivityDTO dto = fixTitle();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 501; i++) sb.append('甲');
        dto.setContent(sb.toString());
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
    }

    @Test
    void create_rejectsUnknownCategory() {
        MktActivityDTO dto = fixTitle();
        dto.setCategory("不存在的分类");
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
    }

    @Test
    void create_rejectsExpireInThePast() {
        MktActivityDTO dto = fixTitle();
        dto.setExpireTime(LocalDateTime.now().minusMinutes(5));
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
    }

    /** 防篡改:单次投放时长 > 12h 必须被后端二次校验拦下,不能信任前端。 */
    @Test
    void create_rejectsExpireBeyond12Hours() {
        MktActivityDTO dto = fixTitle();
        dto.setExpireTime(LocalDateTime.now().plusHours(13));
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
    }

    @Test
    void create_succeedsWithValidInput() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        assertNotNull(id);
        createdId = id;
        MktActivityVO vo = service.detail(id, OTHER);
        assertEquals(MktStatus.LAUNCHING, vo.getStatus());
        assertEquals(dto.getCategory(), vo.getCategory());
    }

    /* ---------------- status 优先级 ---------------- */

    @Test
    void status_priorityFullBeatsPausedAndExpired() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        MktActivity a = mktMapper.selectById(id);

        // 满员:即使开关开着、未过期,也应 FULL
        service.markFull(id, OWNER, true);
        MktActivity full = mktMapper.selectById(id);
        assertEquals(MktStatus.FULL,
                MktStatus.derive(true, true, full.getExpireTime().isBefore(LocalDateTime.now())));

        // 取消满员后,暂停优先于过期:is_enable=0 → PAUSED(即便未过期)
        service.markFull(id, OWNER, false);
        service.toggleEnable(id, OWNER, false);
        MktActivity paused = mktMapper.selectById(id);
        assertEquals(MktStatus.PAUSED,
                MktStatus.derive(false, false, paused.getExpireTime().isBefore(LocalDateTime.now())));

        // 恢复开关:满员=0、开启、未过期 → LAUNCHING
        service.toggleEnable(id, OWNER, true);
        MktActivity on = mktMapper.selectById(id);
        assertEquals(MktStatus.LAUNCHING,
                MktStatus.derive(false, true, on.getExpireTime().isBefore(LocalDateTime.now())));
        assertNotNull(a);
    }

    /* ---------------- reactivate ---------------- */

    @Test
    void reactivate_rejectsOutOfRangeExpire() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        MktReactivDTO r = new MktReactivDTO();
        r.setNewExpire(LocalDateTime.now().minusHours(1)); // 过去 → 400
        assertThrows(BusinessException.class, () -> service.reactivate(id, OWNER, r));
    }

    @Test
    void reactivate_refreshesLastLaunchTime() throws InterruptedException {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        MktActivity before = mktMapper.selectById(id);

        // 等 1s,保证 create 与 reactivate 落在不同的 DB 秒,避免同秒 isAfter 误判
        Thread.sleep(1100);

        MktReactivDTO r = new MktReactivDTO();
        r.setNewExpire(LocalDateTime.now().plusHours(3));
        service.reactivate(id, OWNER, r);

        MktActivity after = mktMapper.selectById(id);
        assertTrue(after.getLastLaunchTime().isAfter(before.getLastLaunchTime()),
                "重激活后 last_launch_time 应被刷新");
        assertEquals(1, after.getIsEnable().intValue(), "重激活后应重新开启");
    }

    /** 重激活 24h/3 次限流:连续第 4 次必须 429(TOO_MANY_REQUESTS)。 */
    @Test
    void reactivate_rateLimitedTo3Per24h() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        MktReactivDTO r = new MktReactivDTO();

        for (int i = 0; i < 3; i++) {
            r.setNewExpire(LocalDateTime.now().plusHours(3));
            assertDoesNotThrow(() -> service.reactivate(id, OWNER, r));
        }
        // 第 4 次:滑动窗口已满,抛 429
        r.setNewExpire(LocalDateTime.now().plusHours(3));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reactivate(id, OWNER, r));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode(),
                "24h 内第 4 次重激活应被限流(429)");
    }

    /* ---------------- 属主越权 ---------------- */

    @Test
    void ownerOnly_opsRejectNonOwner() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;

        // 非属主编辑 → 403
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.edit(id, OTHER, dto));
        assertEquals(ResultCode.MKT_NOT_OWNER.getCode(), e.getCode());

        // 非属主开关/满员/删除/重激活 一律 403
        assertThrows(BusinessException.class, () -> service.toggleEnable(id, OTHER, true));
        assertThrows(BusinessException.class, () -> service.markFull(id, OTHER, true));
        assertThrows(BusinessException.class, () -> service.delete(id, OTHER));
        MktReactivDTO r = new MktReactivDTO();
        r.setNewExpire(LocalDateTime.now().plusHours(3));
        assertThrows(BusinessException.class, () -> service.reactivate(id, OTHER, r));
    }

    /* ---------------- 软删 404 ---------------- */

    @Test
    void delete_softDelete_hidesFromDetailAndPlaza() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        assertNotNull(service.detail(id, OTHER), "删除前详情应可看");

        service.delete(id, OWNER);

        // 软删后详情 → MKT_NOT_FOUND(404 语义)
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.detail(id, OTHER));
        assertEquals(ResultCode.MKT_NOT_FOUND.getCode(), ex.getCode(),
                "软删活动详情应返回 MKT_NOT_FOUND");
    }

    /* ---------------- 留言 + 限流 ---------------- */

    @Test
    void comment_succeedsAndIsRateLimited() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OTHER, dto);
        createdId = id;

        MktCommentDTO c = new MktCommentDTO();
        c.setContent("我有空,一起!");
        service.addComment(id, OWNER, c); // 第 1 条

        c.setContent("周末可以凑齐。");
        service.addComment(id, OWNER, c); // 第 2 条(1min/2 内)

        // 第 3 条:窗口已满 → 429
        c.setContent("再发一条被拦");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addComment(id, OWNER, c));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode(),
                "1 分钟内第 3 条留言应被限流(429)");
    }

    @Test
    void comment_rejectsSensitiveWord() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OTHER, dto);
        createdId = id;

        MktCommentDTO c = new MktCommentDTO();
        // 「赌博」在 sensitive_word 表 level=2,命中拒收
        c.setContent("这里有赌博,来玩");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addComment(id, OWNER, c));
        assertNotNull(ex);
    }
}
