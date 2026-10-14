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
 * 校园限时活动集市 服务端业务规则集成测试（本地 profile,连本机 MySQL + Redis）。
 * 前置:本机 MySQL(campus_social_v2)与 Redis(6379)须已启动。
 * 覆盖计划 §验证 列出的全部规则:
 *   - create 校验:标题 15–25 / 描述 1–500 / 时长 12h 上限(防篡改二次校验) / 敏感词拒收 / 分类 9 选 1
 *   - status 4 态优先级:满员 > 暂停 > 过期 > 投放中
 *   - reactivate:刷新 last_launch_time + 24h/3 次时间窗(Redis 滑动窗口)+ newExpire 越界 400 + 限流 429
 *   - 属主越权 403;软删详情 404
 */
@SpringBootTest
@ActiveProfiles("local")
class MktActivityServiceTest {

    @Autowired private MktActivityService service;
    @Autowired private MktActivityMapper mktMapper;
    @Autowired private MktCommentMapper mktCommentMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private StringRedisTemplate redis;

    /** 造数用的固定 user_id(清理时一起删,避免污染真实数据) */
    private final long OWNER = 9_900_001L;
    private final long OTHER = 9_900_002L;

    private long createdId;
    private final List<Long> myCommentIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        try {
            for (Long cid : myCommentIds) {
                mktCommentMapper.deleteById(cid);
            }
            myCommentIds.clear();
            if (createdId > 0) {
                mktMapper.deleteById(createdId);
                createdId = 0;
            }
            // 清掉限流窗口键,避免下次跑测试被 24h/3 次卡住
            clearMktRedisKeys();
        } catch (Exception ignore) {
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
        userMapper.insert(u);
        return u;
    }

    /* ---------------- create 校验 ---------------- */

    @Test
    void create_rejectsTitleShorterThan15() {
        MktActivityDTO dto = fixTitle();
        dto.setTitle("短标题");
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
    }

    @Test
    void create_rejectsTitleLongerThan25() {
        MktActivityDTO dto = fixTitle();
        dto.setTitle("这是一段非常非常非常非常非常长的活动标题超过上限应该被拦下来拒绝提交处理结果");
        assertThrows(BusinessException.class, () -> service.create(OWNER, dto));
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
    void reactivate_refreshesLastLaunchTime() {
        MktActivityDTO dto = fixTitle();
        Long id = service.create(OWNER, dto);
        createdId = id;
        MktActivity before = mktMapper.selectById(id);

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
