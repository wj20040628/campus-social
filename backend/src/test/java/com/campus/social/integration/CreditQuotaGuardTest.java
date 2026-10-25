package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.CircleDTO;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.CircleService;
import com.campus.social.service.MktActivityService;
import com.campus.social.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布配额守护回归(2026-10-25):建圈在世上限 / 集市在投上限 / 未认证发帖日额。
 * 三条链路都走真实 service(含校验顺序),断言超限抛对应 ResultCode。
 * 自清理自播种;帖子日额的 Redis 计数一并清理。
 */
@SpringBootTest
@ActiveProfiles("local")
class CreditQuotaGuardTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private CircleService circleService;
    @Autowired
    private MktActivityService mktService;
    @Autowired
    private PostService postService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private Long verifiedUserId;
    private Long unverifiedUserId;

    @BeforeEach
    void seed() {
        verifiedUserId = insertUser("quota-ver-" + SUFFIX, 1);
        unverifiedUserId = insertUser("quota-unver-" + SUFFIX, 0);
        for (Long id : new Long[]{verifiedUserId, unverifiedUserId}) {
            jdbc.update("UPDATE user SET create_time = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE id = ?", id);
        }
        // verified 用户基准 60 分(无产出)→ 建圈/在投配额 1
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{verifiedUserId, unverifiedUserId}) {
            if (id == null) continue;
            jdbc.update("DELETE FROM circle_member WHERE user_id = ?", id);
            jdbc.update("DELETE FROM circle WHERE owner_id = ?", id);
            jdbc.update("DELETE FROM mkt_activity WHERE user_id = ?", id);
            jdbc.update("DELETE FROM post WHERE author_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
            redis.delete("post:daily:" + id + ":" + java.time.LocalDate.now().toString().replace("-", ""));
        }
    }

    @Test
    void circleCreationCappedAtQuota() {
        CircleDTO first = new CircleDTO();
        first.setName("配额圈甲" + SUFFIX.substring(0, 3));
        long id = circleService.createCircle(verifiedUserId, first);
        assertTrue(id > 0, "第 1 个圈(配额 1)应成功");

        CircleDTO second = new CircleDTO();
        second.setName("配额圈乙" + SUFFIX.substring(0, 3));
        BusinessException e = assertThrows(BusinessException.class,
                () -> circleService.createCircle(verifiedUserId, second));
        assertEquals(ResultCode.CIRCLE_LIMIT_REACHED.getCode(), e.getCode());
    }

    @Test
    void mktActiveCappedAtQuota() {
        Long id = mktService.create(verifiedUserId, sampleMkt("配额集市一"));
        assertNotNull(id, "第 1 条在投(配额 1)应成功");

        BusinessException e = assertThrows(BusinessException.class,
                () -> mktService.create(verifiedUserId, sampleMkt("配额集市二")));
        assertEquals(ResultCode.MKT_ACTIVE_LIMIT_REACHED.getCode(), e.getCode());
    }

    @Test
    void unverifiedPostDailyCappedAtThree() {
        for (int i = 1; i <= 3; i++) {
            long pid = postService.create(unverifiedUserId, samplePost("第" + i + "条"));
            assertTrue(pid > 0, "第 " + i + " 条(限额 3)应成功");
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> postService.create(unverifiedUserId, samplePost("第四条")));
        assertEquals(ResultCode.POST_DAILY_LIMIT_REACHED.getCode(), e.getCode());
    }

    @Test
    void reactivationAndReEnableCannotBypassActiveQuota() {
        // 配额 1:发 A → 1/1
        Long a = mktService.create(verifiedUserId, sampleMkt("配额集市A"));

        // 暂停 A(腾出在投数)→ 发 B → 1/1,B 在投
        mktService.toggleEnable(a, verifiedUserId, false);
        Long b = mktService.create(verifiedUserId, sampleMkt("配额集市B"));

        // 绕过口 1:重激活 A(过期/暂停态)→ 应被拦
        com.campus.social.dto.MktReactivDTO rd = new com.campus.social.dto.MktReactivDTO();
        rd.setNewExpire(LocalDateTime.now().plusHours(2));
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> mktService.reactivate(a, verifiedUserId, rd));
        assertEquals(ResultCode.MKT_ACTIVE_LIMIT_REACHED.getCode(), e1.getCode());

        // 绕过口 2:重新开启暂停中的 A → 应被拦
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> mktService.toggleEnable(a, verifiedUserId, true));
        assertEquals(ResultCode.MKT_ACTIVE_LIMIT_REACHED.getCode(), e2.getCode());

        // 合法续期:对**在投中**的 B 重激活(=延长到期),不增加在投数,应放行
        mktService.reactivate(b, verifiedUserId, rd);

        // 删 B 腾名额后,重激活 A 放行(0/1 → 1/1)
        mktService.delete(b, verifiedUserId);
        mktService.reactivate(a, verifiedUserId, rd);
    }

    private MktActivityDTO sampleMkt(String title) {
        MktActivityDTO dto = new MktActivityDTO();
        dto.setCategory("游戏组队");
        dto.setTitle(title);
        dto.setContent("配额守护测试活动,跑完即清");
        dto.setExpireTime(LocalDateTime.now().plusHours(2));
        return dto;
    }

    private CreatePostDTO samplePost(String tag) {
        CreatePostDTO dto = new CreatePostDTO();
        dto.setContent("配额守护测试帖 " + tag + ",跑完即清");
        dto.setTopics(List.of());
        return dto;
    }
}
