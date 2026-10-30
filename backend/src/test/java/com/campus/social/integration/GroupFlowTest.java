package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.service.GroupService;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import com.campus.social.common.CursorPage;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群管理/群消息流程契约（2026-10-30，拆分 GroupServiceImpl 前补的特征测试）。
 *
 * <p>此前只有「建群门槛 + 邀请关系」有测试，改名/公告/踢人/转让/退群/
 * 禁言/发消息/撤回/已读全靠手测。拆成多个服务前先把这些行为钉住。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class GroupFlowTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupMapper groupMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();

    private long owner;

    @BeforeEach
    void setUp() {
        owner = user();
    }

    @AfterEach
    void cleanup() {
        for (Long gid : groupIds) {
            jdbc.update("DELETE FROM group_message WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM group_read WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM group_member WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM `group` WHERE id = ?", gid);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM notify WHERE user_id = ?", id);
            jdbc.update("DELETE FROM relation WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM chat_conv_pref WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        groupIds.clear();
        userIds.clear();
    }

    /** 注册满 4 天的老号：通过建群/拉人的行为门槛 */
    private long user() {
        String name = "gf-" + userIds.size() + "-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,0,0,1,0,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                name, "x", name);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, name);
        userIds.add(id);
        return id;
    }

    /** owner 建群并拉入 members（先建关注关系，邀请校验才放行） */
    private long group(long... members) {
        for (long uid : members) {
            jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)",
                    owner, uid);
        }
        long gid = groupService.createGroup(owner, "流程群-" + SUFFIX + "-" + groupIds.size(), null,
                java.util.Arrays.stream(members).boxed().toList());
        groupIds.add(gid);
        return gid;
    }

    private int memberRowCount(long gid, long uid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE group_id = ? AND user_id = ?",
                Integer.class, gid, uid);
    }

    private Integer groupMemberCount(long gid) {
        return jdbc.queryForObject("SELECT member_count FROM `group` WHERE id = ?", Integer.class, gid);
    }

    @Test
    void renameAndAnnouncementOnlyOwner() {
        long gid = group();
        groupService.rename(gid, owner, "新群名-" + SUFFIX);
        groupService.setAnnouncement(gid, owner, "  公告内容  ");

        GroupMemberInfoVO info = groupService.members(gid, owner);
        assertEquals("新群名-" + SUFFIX, info.getName());
        assertEquals("公告内容", info.getAnnouncement(), "公告应 trim");

        long m = user();
        jdbc.update("INSERT INTO group_member (group_id, user_id, role) VALUES (?,?,3)", gid, m);
        jdbc.update("UPDATE `group` SET member_count = member_count + 1 WHERE id = ?", gid);
        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.rename(gid, m, "越权改名"));
        assertEquals(ResultCode.NOT_GROUP_OWNER.getCode(), e.getCode());
    }

    @Test
    void kickRemovesMemberAndDecrementsCount() {
        long m1 = user();
        long m2 = user();
        long gid = group(m1, m2);
        assertEquals(Integer.valueOf(3), groupMemberCount(gid));

        groupService.kick(gid, owner, m2);
        assertEquals(0, memberRowCount(gid, m2), "被踢成员行应删除");
        assertEquals(Integer.valueOf(2), groupMemberCount(gid));

        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.sendGroupMessage(m2, gid, "我还在吗", null));
        assertEquals(ResultCode.NOT_GROUP_MEMBER.getCode(), e.getCode());
        // 重复踢：幂等，不再报错
        groupService.kick(gid, owner, m2);
    }

    @Test
    void transferFlipsOwnerAndRoles() {
        long m1 = user();
        long gid = group(m1);

        groupService.transfer(gid, owner, m1);
        assertEquals(m1, groupMapper.selectById(gid).getOwnerId());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT role FROM group_member WHERE group_id = ? AND user_id = ?", Integer.class, gid, m1));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT role FROM group_member WHERE group_id = ? AND user_id = ?", Integer.class, gid, owner));

        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.rename(gid, owner, "原群主改名"));
        assertEquals(ResultCode.NOT_GROUP_OWNER.getCode(), e.getCode());
    }

    @Test
    void memberLeaveRemovesSelf() {
        long m1 = user();
        long gid = group(m1);

        groupService.leaveGroup(gid, m1);
        assertEquals(0, memberRowCount(gid, m1));
        assertEquals(Integer.valueOf(1), groupMemberCount(gid), "群主仍保留");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_read WHERE group_id = ? AND user_id = ?", Integer.class, gid, m1));
    }

    @Test
    void ownerLeaveTransfersToRemainingMember() {
        long m1 = user();
        long m2 = user();
        long gid = group(m1, m2);

        groupService.leaveGroup(gid, owner);
        assertEquals(0, memberRowCount(gid, owner), "退群群主行应删除");
        Long newOwner = groupMapper.selectById(gid).getOwnerId();
        assertNotNull(newOwner);
        assertTrue(newOwner.equals(m1) || newOwner.equals(m2), "应自动转让给剩余成员");
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT role FROM group_member WHERE group_id = ? AND user_id = ?",
                Integer.class, gid, newOwner));
    }

    @Test
    void lastMemberOwnerLeaveDisbands() {
        long gid = group();
        groupService.leaveGroup(gid, owner);

        assertNull(groupMapper.selectById(gid), "只剩群主时退群=解散");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_member WHERE group_id = ?", Integer.class, gid));
    }

    @Test
    void muteBlocksSendUntilUnmuted() {
        long m1 = user();
        long m2 = user();
        long gid = group(m1, m2);

        groupService.mute(gid, owner, m1, true);
        assertTrue(groupService.isMuted(gid, m1));
        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.sendGroupMessage(m1, gid, "被禁言", null));
        assertEquals(ResultCode.GROUP_MUTED.getCode(), e.getCode());

        // 群主恒可发言，即使被误标禁言
        jdbc.update("UPDATE group_member SET muted = 1 WHERE group_id = ? AND user_id = ?", gid, owner);
        assertFalse(groupService.isMuted(gid, owner));
        assertNotNull(groupService.sendGroupMessage(owner, gid, "群主发言", null).getMsgId());

        groupService.muteAll(gid, owner, true);
        BusinessException all = assertThrows(BusinessException.class,
                () -> groupService.sendGroupMessage(m2, gid, "全员禁言", null));
        assertEquals(ResultCode.GROUP_MUTED.getCode(), all.getCode());

        groupService.mute(gid, owner, m1, false);
        assertFalse(groupService.isMuted(gid, m1));
        assertNotNull(groupService.sendGroupMessage(m1, gid, "解禁后发言", null).getMsgId());
    }

    @Test
    void sendReadRecallFlow() {
        long m1 = user();
        long gid = group(m1);

        long msg1 = groupService.sendGroupMessage(owner, gid, "第一条", null).getMsgId();
        long msg2 = groupService.sendGroupMessage(owner, gid, "第二条", msg1).getMsgId();

        CursorPage<GroupMessageVO> page = groupService.groupMessages(gid, m1, null, 20);
        assertEquals(2, page.getList().size());
        assertEquals("第一条", page.getList().get(0).getContent(), "按时间正序");
        assertFalse(page.getList().get(0).isFromMe());
        assertEquals("第一条", page.getList().get(1).getReplyToContent(), "引用片段应回填");

        CursorPage<GroupVO> mine = groupService.myGroups(m1, null, 20);
        assertEquals(1, mine.getList().size());
        assertEquals(2, mine.getList().get(0).getMyUnread(), "两条非本人消息都是未读");
        groupService.markGroupRead(m1, gid);
        assertEquals(0, groupService.myGroups(m1, null, 20).getList().get(0).getMyUnread(), "已读后清零");

        // 只能撤回自己的
        BusinessException other = assertThrows(BusinessException.class,
                () -> groupService.recallGroupMessage(m1, msg2));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), other.getCode());
        // 超过 120 秒不能撤回
        jdbc.update("UPDATE group_message SET create_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE) WHERE id = ?", msg1);
        BusinessException expired = assertThrows(BusinessException.class,
                () -> groupService.recallGroupMessage(owner, msg1));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), expired.getCode());
        // 窗口内撤回：出参置空
        groupService.recallGroupMessage(owner, msg2);
        GroupMessageVO recalled = groupService.groupMessages(gid, m1, null, 20).getList().get(1);
        assertTrue(recalled.getRecalled());
        assertEquals("", recalled.getContent());
    }

    @Test
    void replyMustPointToSameGroup() {
        long gid = group();
        long other = group();
        long foreign = groupService.sendGroupMessage(owner, other, "别的群", null).getMsgId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> groupService.sendGroupMessage(owner, gid, "跨群引用", foreign));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), e.getCode());
    }
}
