package com.campus.social.integration;

import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资料维护特征测试：me / 昵称与介绍 / 封面「传什么就是什么」/ 陌生人开关。
 *
 * <p>用于 ProfileService 抽取前后锁定行为，种子数据自播种自清理。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ProfileFlowTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long uid;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setUsername("profile-" + System.nanoTime() + "@campus.test");
        u.setPasswordHash("x");
        u.setNickname("原昵称");
        u.setStatus(0);
        u.setStrangerMsgEnabled(1);
        u.setCoverUrl("https://cdn.example.com/old.png");
        userMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        if (uid != null) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM user WHERE id = ?", uid);
        }
    }

    @Test
    void meReturnsPrivateProfile() {
        UserVO vo = authService.me(uid);
        assertEquals(uid, vo.getId());
        assertEquals("原昵称", vo.getNickname());
        assertEquals(0, vo.getRole());
    }

    @Test
    void updateProfileWritesNicknameAndTrimmedBioWithAudit() {
        authService.updateProfile(uid, "新昵称", null, null, "  这个人很懒  ");

        User after = userMapper.selectById(uid);
        assertEquals("新昵称", after.getNickname());
        assertEquals("这个人很懒", after.getBio(), "介绍应去掉首尾空白后落库");
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'update_profile'",
                Integer.class, uid);
        assertTrue(audit != null && audit >= 1, "改资料应写审计");
    }

    @Test
    void updateCoverBlankRestoresDefaultAndExternalUrlAccepted() {
        UserVO restored = authService.updateCover(uid, "   ");
        assertNull(restored.getCoverUrl());
        assertNull(userMapper.selectById(uid).getCoverUrl(), "空白应落成 NULL（恢复默认）");

        String next = "https://cdn.example.com/new.png";
        UserVO changed = authService.updateCover(uid, next);
        assertEquals(next, changed.getCoverUrl());
        assertEquals(next, userMapper.selectById(uid).getCoverUrl(), "外部图床 URL 应放行");
    }

    @Test
    void strangerMsgSwitchPersistsAndAudits() {
        authService.setStrangerMsgEnabled(uid, false);

        assertEquals(0, userMapper.selectById(uid).getStrangerMsgEnabled());
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'set_stranger_msg'",
                Integer.class, uid);
        assertTrue(audit != null && audit >= 1, "开关变更应写审计");
    }
}
