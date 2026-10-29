package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.SensitiveWord;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.SensitiveWordMapper;
import com.campus.social.service.ChatGuardService;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.ChatSendVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 搭话护栏闭环（2026-10-28 新建）。
 *
 * <p>这一轮把「解锁条件」整个换了，四条规则各自都容易写反：
 * <ol>
 *   <li>对方关注我 → 解锁</li>
 *   <li>对方回复过我 → 解锁</li>
 *   <li><b>我关注对方 → 不解锁</b>（最容易写错的一条：关注是单向动作，
 *       不能替对方表达意愿；写成双向判定等于给「关注」开一个免费绕过开关）</li>
 *   <li>接收方陌生人开关只对**真正的陌生人**生效 —— 对方已经关注/回复过，
 *       说明他自己开了门，不该再被自己的开关挡住</li>
 * </ol>
 * 外加<b>敏感词必须在配额之前</b>：一条被拒的脏话不该吃掉当天唯一的一条机会。
 */
@SpringBootTest
@ActiveProfiles("local")
class ChatGuardIntegrationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    /** 测试专用拒发词：造一个真实词库里不会出现的串，避免依赖词库内容 */
    private static final String BLOCKED_WORD = "测试拒发词甲";

    @Autowired
    private ChatGuardService guard;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SensitiveWordFilter sensitiveFilter;
    @Autowired
    private SensitiveWordMapper sensitiveWordMapper;

    private final List<Long> userIds = new ArrayList<>();
    private Long tmpWordId;

    @BeforeEach
    void seedSensitiveWord() {
        SensitiveWord w = new SensitiveWord();
        w.setWord(BLOCKED_WORD);
        w.setLevel(2);
        sensitiveWordMapper.insert(w);
        tmpWordId = w.getId();
        // DFA 是启动时加载的内存结构，新增词必须显式重载，否则这条用例恒假
        sensitiveFilter.load();
    }

    @AfterEach
    void cleanup() {
        if (tmpWordId != null) {
            jdbc.update("DELETE FROM sensitive_word WHERE id = ?", tmpWordId);
            sensitiveFilter.load();
        }
        for (Long id : userIds) {
            Set<String> keys = redis.keys("quota:stranger:perpeer:" + id + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM chat_message WHERE sender_id = ? OR receiver_id = ?", id, id);
            jdbc.update("DELETE FROM notify WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        userIds.clear();
    }

    /* ------------------------------------------------------------------ 播种 */

    private Long user(int verifyStatus, int strangerMsgEnabled) {
        String name = "cg-" + verifyStatus + "-" + userIds.size() + "-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,?,0)",
                name, "x", name, verifyStatus, strangerMsgEnabled);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, name);
        userIds.add(id);
        return id;
    }

    /** 关系行：type 1=关注 2=好友 3=黑名单 */
    private void relate(long from, long to, int type) {
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,?,0)",
                from, to, type);
    }

    private void peerReplied(long peer, long me) {
        jdbc.update("INSERT INTO chat_message (conv_id, sender_id, receiver_id, content, is_deleted) "
                + "VALUES (?,?,?,?,0)", 900_000_000L + peer, peer, me, "我先回你一句");
    }

    private void assertCode(ResultCode expected, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(expected.getCode(), ex.getCode(), "错误码不符，实际文案：" + ex.getMessage());
    }

    /* ------------------------------------------------------------------ 用例 */

    /** 陌生人：第 1 条通过（剩余 0），第 2 条 429 */
    @Test
    void strangerGetsOneMessagePerDay() {
        long me = user(1, 1);
        long peer = user(1, 1);

        ChatSendVO first = guard.check(me, peer, "你好，看到你也在找自习搭子");
        assertEquals(Boolean.TRUE, first.getIsStranger());
        assertNotNull(first.getRemainingPerPeerQuota());
        assertEquals(0, first.getRemainingPerPeerQuota().intValue());

        assertCode(ResultCode.TOO_MANY_REQUESTS, () -> guard.check(me, peer, "再问一句"));
    }

    /** 对方关注我 → 解锁，不限条数，回执里没有额度字段 */
    @Test
    void peerFollowingMeUnlocks() {
        long me = user(1, 1);
        long peer = user(1, 1);
        relate(peer, me, 1);

        ChatSendVO vo = guard.check(me, peer, "第一条");
        assertEquals(Boolean.FALSE, vo.getIsStranger());
        assertNull(vo.getRemainingPerPeerQuota(), "已解锁就不该再有剩余条数");

        // 连发三条都不受限
        guard.check(me, peer, "第二条");
        guard.check(me, peer, "第三条");
    }

    /**
     * <b>我关注对方 → 仍然受限</b>。
     * 这条是「关注当免费开关」那个洞的回归：判定若写成双向，
     * 任何人只要点一下关注就能对同一个人无限发消息。
     */
    @Test
    void myFollowingDoesNotUnlock() {
        long me = user(1, 1);
        long peer = user(1, 1);
        relate(me, peer, 1);

        ChatSendVO vo = guard.check(me, peer, "第一条");
        assertEquals(Boolean.TRUE, vo.getIsStranger(), "我关注对方不算解锁");

        assertCode(ResultCode.TOO_MANY_REQUESTS, () -> guard.check(me, peer, "第二条"));
    }

    /** 对方回复过我 → 解锁 */
    @Test
    void peerReplyUnlocks() {
        long me = user(1, 1);
        long peer = user(1, 1);
        peerReplied(peer, me);

        ChatSendVO vo = guard.check(me, peer, "你好");
        assertEquals(Boolean.FALSE, vo.getIsStranger());
        assertNull(vo.getRemainingPerPeerQuota());
    }

    /** 对方关闭陌生人消息 + 双方无关系 → 1030 */
    @Test
    void closedStrangerSwitchBlocksStrangers() {
        long me = user(1, 1);
        long peer = user(1, 0);

        assertCode(ResultCode.STRANGER_MSG_CLOSED, () -> guard.check(me, peer, "你好"));
    }

    /**
     * 对方关了开关但关注了我 → 放行。
     * 开关的语义是「不让陌生人找我」，对方关注我说明他已主动开门，
     * 用他的开关把我挡住等于让他自己否掉自己的动作。
     */
    @Test
    void closedStrangerSwitchDoesNotBlockOptedInPeer() {
        long me = user(1, 1);
        long peer = user(1, 0);
        relate(peer, me, 1);

        ChatSendVO vo = guard.check(me, peer, "你好");
        assertEquals(Boolean.FALSE, vo.getIsStranger());
    }

    /** 未认证发送方：1047 拦在最前，不受任何关系影响 */
    @Test
    void unverifiedSenderIsRejected() {
        long me = user(0, 1);
        long peer = user(1, 1);

        assertCode(ResultCode.USER_NOT_VERIFIED, () -> guard.check(me, peer, "你好"));
    }

    /** 黑名单（任一方向）→ 拒绝 */
    @Test
    void blacklistEitherDirectionBlocks() {
        long me = user(1, 1);
        long peer = user(1, 1);
        relate(peer, me, 3);

        assertCode(ResultCode.BLACKLISTED, () -> guard.check(me, peer, "你好"));
    }

    /**
     * 敏感词拒发**不扣额度**。
     *
     * <p>旧实现先扣额度再查词，一条脏话就吃掉当天唯一一条机会；
     * 用户看到的是「今天不能再发了」而不是「这句话违规」。
     */
    @Test
    void blockedSensitiveWordDoesNotConsumeQuota() {
        long me = user(1, 1);
        long peer = user(1, 1);

        assertCode(ResultCode.SENSITIVE_WORD_BLOCKED,
                () -> guard.check(me, peer, "这句里有" + BLOCKED_WORD + "，应该被拦"));

        // 额度没被动过：正常内容仍能发出第 1 条
        ChatSendVO ok = guard.check(me, peer, "这句是干净的");
        assertEquals(0, ok.getRemainingPerPeerQuota().intValue(),
                "被拦下的那句不该占掉今天的名额");
    }

    /** 不能给自己发 */
    @Test
    void selfMessageIsRejected() {
        long me = user(1, 1);
        assertCode(ResultCode.CHAT_SELF, () -> guard.check(me, me, "自言自语"));
    }
}
