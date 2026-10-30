package com.campus.social.integration;

import com.campus.social.common.ReportTargetType;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.GroupService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportHandleVO;
import com.campus.social.vo.ReportSubmitVO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊行为门槛 + 拉人关系限制 + 群消息举报。
 *
 * <p>三件事各对应一条真实的绕过路径：
 * <ol>
 *   <li>建群/拉人有账号年龄门槛：2026-10-30 起校园信息退出权限判定，
 *       改为「注册满 3 天 + 信誉分达线」。这里锁住新号不能建群，
 *       防止「注册即建群 → 批量拉陌生人 → 发广告」的骚扰链路。</li>
 *   <li>拉人必须存在关注关系：任意群成员把任意 userId 塞进群且对方
 *       毫不知情，会形成同样的骚扰链路。</li>
 *   <li>群消息举报用 {@code targetType=8}，<b>不能复用 4</b>：两张表各自自增 id，
 *       同一个 id 在私信表与群消息表里指向完全不同的内容，复用会封错人。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class GroupGateAndReportIntegrationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    /** 通知类型：18 处置结果 / 22 群聊邀请（与 GroupServiceImpl、NotifyServiceImpl 一致） */
    private static final int NOTIFY_REPORT_RESULT = 18;
    private static final int NOTIFY_GROUP_INVITE = 22;
    private static final long ADMIN_ID = 1L;

    @Autowired
    private GroupService groupService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        /* 无成员建群：成员关系在用例里按需补，避免播种阶段就被门槛挡住 */
    }

    @AfterEach
    void cleanup() {
        for (Long gid : groupIds) {
            jdbc.update("DELETE FROM group_message WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM group_member WHERE group_id = ?", gid);
            jdbc.update("DELETE FROM `group` WHERE id = ?", gid);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM report WHERE reporter_id = ? OR target_id IN "
                    + "(SELECT id FROM group_message WHERE sender_id = ?)", id, id);
            jdbc.update("DELETE FROM notify WHERE user_id = ?", id);
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        groupIds.clear();
        userIds.clear();
    }

    /** 注册满 4 天的老号：通过建群/拉人的账号年龄门槛 */
    private Long user(int verifyStatus) {
        String name = "gg-" + verifyStatus + "-" + userIds.size() + "-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, create_time) "
                        + "VALUES (?,?,?,0,0,1,?,1,0, DATE_SUB(NOW(), INTERVAL 4 DAY))",
                name, "x", name, verifyStatus);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, name);
        userIds.add(id);
        return id;
    }

    /** 刚注册的号 */
    private Long freshUser() {
        String name = "gg-fresh-" + userIds.size() + "-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,0,1,0)",
                name, "x", name);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, name);
        userIds.add(id);
        return id;
    }

    private long group(Long ownerId) {
        long gid = groupService.createGroup(ownerId, "测试群-" + SUFFIX + "-" + groupIds.size(), null, List.of());
        groupIds.add(gid);
        return gid;
    }

    private void relate(long from, long to, int type) {
        jdbc.update("INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,?,0)",
                from, to, type);
    }

    private int notifyCount(Long userId, int type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notify WHERE user_id = ? AND type = ?",
                Integer.class, userId, type);
    }

    /* ------------------------------------------------- 1. 行为门槛（账号年龄） */

    /** 新号建群 → 403 ACCOUNT_TOO_NEW；注册满 3 天后才放行 */
    @Test
    void brandNewAccountCannotCreateGroup() {
        long fresh = freshUser();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.createGroup(fresh, "新号群-" + SUFFIX, null, List.of()));
        assertEquals(ResultCode.ACCOUNT_TOO_NEW.getCode(), ex.getCode(),
                "注册不足 3 天的账号不能建群");
        assertTrue(ex.getMessage().contains("注册满 3 天"), ex.getMessage());
    }

    /** 老号不依赖校园信息即可建群：verify_status=0 也能成功 */
    @Test
    void establishedUnverifiedUserCanCreateGroup() {
        long owner = user(0);
        long gid = group(owner);
        assertNotNull(gid);
    }

    /* ------------------------------------------------- 2. 拉人关系限制 */

    /** 无任何关系 → 422，一个人都不该进群 */
    @Test
    void invitingStrangerIsRejected() {
        long owner = user(1);
        long stranger = user(1);
        long gid = group(owner);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> groupService.addMembers(gid, owner, List.of(stranger)));
        assertEquals(ResultCode.UNPROCESSABLE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("只能邀请关注你或你关注的人"), ex.getMessage());

        assertEquals(0, memberCount(gid, stranger), "被拒的邀请不能留下成员行");
    }

    /** 我关注对方 → 可以拉，且对方收到 type=22 群聊邀请通知 */
    @Test
    void invitingFollowedUserSucceedsWithNotify() {
        long owner = user(1);
        long target = user(1);
        relate(owner, target, 1);
        long gid = group(owner);

        int added = groupService.addMembers(gid, owner, List.of(target));
        assertEquals(1, added);
        assertEquals(1, memberCount(gid, target), "邀请成功后应落成员行");
        assertEquals(1, notifyCount(target, NOTIFY_GROUP_INVITE),
                "被拉进群的人必须收到通知，否则他会莫名其妙多出一个群");
    }

    /** 对方关注我（反向）同样可以拉 */
    @Test
    void invitingFollowerSucceeds() {
        long owner = user(1);
        long target = user(1);
        relate(target, owner, 1);
        long gid = group(owner);

        assertEquals(1, groupService.addMembers(gid, owner, List.of(target)));
    }

    /* ------------------------------------------------- 3. 群消息举报（type=8） */

    private long groupMessage(long gid, long sender) {
        jdbc.update("INSERT INTO group_message (group_id, sender_id, content, is_deleted) VALUES (?,?,?,0)",
                gid, sender, "群里的广告-" + SUFFIX);
        return jdbc.queryForObject("SELECT id FROM group_message WHERE group_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, gid);
    }

    private int messageDeleted(long msgId) {
        return jdbc.queryForObject("SELECT is_deleted FROM group_message WHERE id = ?", Integer.class, msgId);
    }

    /**
     * 提交 type=8 举报 → 管理员 takedown → 群消息软删、举报人收到 18 回执。
     *
     * <p>这条链路的价值在于「下架的是群消息表那一行」：若类型编号退回复用 4，
     * 同样的 id 会打到 chat_message 上，群消息原样留着、私信反倒被删。
     */
    @Test
    void groupMessageReportCanBeTakenDown() {
        long owner = user(1);
        long reporter = user(1);
        long gid = group(owner);
        long msgId = groupMessage(gid, owner);

        ReportSubmitDTO submit = new ReportSubmitDTO();
        submit.setTargetType(ReportTargetType.GROUP_MESSAGE);
        submit.setTargetId(msgId);
        submit.setReasonType(2); // 广告
        submit.setReasonText("群里发广告");

        ReportSubmitVO created = reportService.submit(reporter, submit);
        assertNotNull(created.getReportId());

        ReportHandleDTO handle = new ReportHandleDTO();
        handle.setAction("takedown");
        handle.setHandleNote("群消息广告，下架");
        ReportHandleVO result = reportService.handle(ADMIN_ID, created.getReportId(), handle);
        assertNotNull(result);

        assertEquals(1, messageDeleted(msgId), "群消息应被软删（列表按 is_deleted 过滤）");
        assertEquals(1, notifyCount(reporter, NOTIFY_REPORT_RESULT), "举报人应收到处置结果回执");
    }

    /** 举报 8 与 4 的处置对象必须落在各自的表上：只插群消息时，私信表不该被误删 */
    @Test
    void groupMessageTypeDoesNotTouchChatMessage() {
        long owner = user(1);
        long reporter = user(1);
        long gid = group(owner);
        long msgId = groupMessage(gid, owner);

        ReportSubmitDTO submit = new ReportSubmitDTO();
        submit.setTargetType(ReportTargetType.GROUP_MESSAGE);
        submit.setTargetId(msgId);
        submit.setReasonType(1);
        ReportSubmitVO created = reportService.submit(reporter, submit);

        ReportHandleDTO handle = new ReportHandleDTO();
        handle.setAction("takedown");
        reportService.handle(ADMIN_ID, created.getReportId(), handle);

        Integer chatTouched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE id = ? AND is_deleted = 1", Integer.class, msgId);
        assertEquals(0, chatTouched, "群消息举报不能把同 id 的私信删掉");
    }

    private int memberCount(long gid, long uid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE group_id = ? AND user_id = ? "
                + "AND is_deleted = 0", Integer.class, gid, uid);
    }
}
