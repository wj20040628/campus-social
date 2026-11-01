package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.SensitiveWord;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.SensitiveWordMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.util.SensitiveWordFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 昵称/简介敏感词口径：昵称一级二级都拒，简介二级拒发、一级打码。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ProfileSensitiveWordTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 1_000_000);
    private static final String BLOCK_WORD = "资料拒发词" + SUFFIX;
    private static final String MASK_WORD = "资料替换词" + SUFFIX;

    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SensitiveWordFilter sensitiveFilter;
    @Autowired
    private SensitiveWordMapper sensitiveWordMapper;

    private final List<Long> wordIds = new ArrayList<>();
    private Long uid;

    @BeforeEach
    void seed() {
        wordIds.add(sensitiveWord(BLOCK_WORD, 2));
        wordIds.add(sensitiveWord(MASK_WORD, 1));
        // DFA 是启动时加载的内存结构，测试词必须显式重载
        sensitiveFilter.load();

        User u = new User();
        u.setUsername("profile-sw-" + SUFFIX + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("原昵称");
        u.setStatus(0);
        u.setStrangerMsgEnabled(1);
        userMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        if (uid != null) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
        for (Long id : wordIds) {
            jdbc.update("DELETE FROM sensitive_word WHERE id = ?", id);
        }
        wordIds.clear();
        sensitiveFilter.load();
    }

    private Long sensitiveWord(String word, int level) {
        SensitiveWord w = new SensitiveWord();
        w.setWord(word);
        w.setLevel(level);
        sensitiveWordMapper.insert(w);
        return w.getId();
    }

    @Test
    void nicknameWithBlockWordIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.updateProfile(uid, "前缀" + BLOCK_WORD, null, null, null));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());
        assertEquals("原昵称", userMapper.selectById(uid).getNickname());
    }

    @Test
    void nicknameWithReplaceWordIsAlsoRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.updateProfile(uid, "前缀" + MASK_WORD, null, null, null));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());
        assertEquals("原昵称", userMapper.selectById(uid).getNickname());
    }

    @Test
    void bioWithBlockWordIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.updateProfile(uid, null, null, null, "介绍" + BLOCK_WORD));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());
        assertNull(userMapper.selectById(uid).getBio());
    }

    @Test
    void bioWithReplaceWordIsMaskedAndSaved() {
        authService.updateProfile(uid, null, null, null, "介绍" + MASK_WORD);

        String stored = userMapper.selectById(uid).getBio();
        assertEquals("介绍" + "*".repeat(MASK_WORD.length()), stored);
        assertNotNull(stored);
        assertTrue(!stored.contains(MASK_WORD), "一级词不应原样落库");
    }
}
