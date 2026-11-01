package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.SensitiveWord;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.SensitiveWordMapper;
import com.campus.social.service.GroupService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 群名称敏感词口径：建群与改名都拦一级/二级词，不打码入库。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class GroupSensitiveWordTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 1_000_000);
    private static final String BLOCK_WORD = "群名拒发词" + SUFFIX;
    private static final String MASK_WORD = "群名替换词" + SUFFIX;

    @Autowired
    private GroupService groupService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SensitiveWordFilter sensitiveFilter;
    @Autowired
    private SensitiveWordMapper sensitiveWordMapper;

    private final List<Long> wordIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private Long owner;

    @BeforeEach
    void seed() {
        wordIds.add(sensitiveWord(BLOCK_WORD, 2));
        wordIds.add(sensitiveWord(MASK_WORD, 1));
        sensitiveFilter.load();

        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,0,0,1,0,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                "group-sw-" + SUFFIX + "@campus.test", "x", "群名测试");
        owner = jdbc.queryForObject("SELECT id FROM user WHERE username = ?",
                Long.class, "group-sw-" + SUFFIX + "@campus.test");
    }

    @AfterEach
    void cleanup() {
        for (Long gid : groupIds) {
            jdbc.update("DELETE FROM group_member WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM `group` WHERE id = ?", gid);
        }
        groupIds.clear();
        if (owner != null) {
            jdbc.update("DELETE FROM `group` WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM user WHERE id = ?", owner);
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
    void createGroupRejectsBlockWord() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.createGroup(owner, "前缀" + BLOCK_WORD, null, List.of()));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());
    }

    @Test
    void createGroupRejectsReplaceWordToo() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.createGroup(owner, "前缀" + MASK_WORD, null, List.of()));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());
    }

    @Test
    void renameRejectsSensitiveWordAndKeepsOldName() {
        long gid = groupService.createGroup(owner, "正常群名" + SUFFIX, null, List.of());
        groupIds.add(gid);

        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.rename(gid, owner, "前缀" + BLOCK_WORD));
        assertEquals(ResultCode.SENSITIVE_WORD_BLOCKED.getCode(), e.getCode());

        String name = jdbc.queryForObject("SELECT name FROM `group` WHERE id = ?", String.class, gid);
        assertEquals("正常群名" + SUFFIX, name);
    }
}
