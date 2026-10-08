package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.io.Serializable;

@Data
@TableName("user")
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮箱或手机号 */
    private String username;

    /** BCrypt */
    private String passwordHash;

    /** 学号(可选),UNIQUE */
    private String studentNo;

    private String nickname;
    private String avatarUrl;

    /**
     * 年级与院系：<b>id 是权威值，名称是展示快照</b>。
     *
     * <p>{@code dept} / {@code grade} 两个字符串列保留下来是为了兼容
     * 既有列表查询（推荐理由、帖子作者卡、搜索结果都在直接读它们），
     * 避免为了一次字典化把七八处查询全改成 join。它们由 service 在写入时
     * 从字典表取名一并落库，不再接受用户输入。
     *
     * <p>匹配（AC-14 同院系 / 同年级）一律用 {@code deptId} / {@code gradeId}：
     * 院系改名是真实事件（合并、更名），存名称会让改名后的新老用户匹配不上，
     * 存 id 则改名只影响展示文本，不影响任何匹配逻辑。
     */
    private String grade;

    private String dept;

    /** 院系字典 id，指向 school_dept.id。匹配与筛选的权威依据 */
    private Long deptId;

    /** 年级字典 id，指向 school_grade.id */
    private Long gradeId;

    /** 0正常 1冻结 2注销 */
    private Integer status;

    /** 角色:0普通用户 1管理员 */
    private Integer role;

    /** 学校ID,MVP 单校恒为 1,预留多校(migration_v1.sql 第4节) */
    private Long schoolId;

    /** 校内认证状态:0未认证 1已认证 2审核中 3驳回(AC-11 只有 1 才可发起搭话与发公告) */
    private Integer verifyStatus;

    /**
     * 是否接收陌生人消息:0否 1是(migration_v1.sql 第4节)。
     * <p>F3 强制护栏——关闭后陌生人发送直接拒。没有这个开关,
     * 免好友搭话上线即变成骚扰工具。
     */
    private Integer strangerMsgEnabled;

    /** 引导进度:0未开始 1已填归属 2已选标签 3已完成(ARCHITECTURE §3 Q10.1) */
    private Integer onboardingStep;

    private Integer followerCount;
    private Integer followingCount;
    private Integer unreadNotifyCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
