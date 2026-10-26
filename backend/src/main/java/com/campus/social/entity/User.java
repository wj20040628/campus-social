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

    /**
     * 登录主账号:重构后内部统一存「学号」(账号体系重构 2026-10-17)。
     *
     * <p>历史数据里这列可能仍是旧主标识(邮箱/手机号),因为没强制回填;
     * 登录查询用 {@code username OR email} 双匹配,旧账号靠原 username 仍可登录,
     * 新账号一律写学号。脱敏展示走 {@code UserVoAssembler.maskUsername}
     * (学号 13/14 位会按 {@code 2308****0678} 形态脱敏)。
     */
    private String username;

    /** BCrypt */
    private String passwordHash;

    /** 学号(可选),UNIQUE。重构后新账号 username 与 studentNo 同值;旧账号可能为空 */
    private String studentNo;

    /**
     * 安全凭证邮箱(账号体系重构 2026-10-17)。
     *
     * <p>只承担找回密码 / 绑定 / 已绑定用户的邮箱验证码登录三种职责,
     * 不参与日常学号密码登录。有值时 {@code uk_user_email} 唯一,
     * 防止两个账号绑定同一邮箱。存库前统一转小写。
     */
    private String email;

    private String nickname;
    /**
     * 个人介绍（2026-10-19）。
     *
     * <p>上限 200 字，取 200 而不是更长：主页身份头下方一行最多显示两行，
     * 超过两行的内容在手机上会被截断，而用户不会知道自己的介绍只露出一半。
     *
     * <p>允许为空（没写介绍的人很多，不该逼着填）。空时前端显示引导文案。
     */
    private String bio;
    private String avatarUrl;

    /**
     * 个人主页自定义封面（2026-10-20）。
     *
     * <p>{@code null} 表示「没设置」，前端用默认渐变封面（{@code --ys-cover-gradient}）。
     * 不用空串表示没设置：空串会让前端拼出 {@code url('')}，浏览器会当成
     * 「对当前文档再发一次请求」，在网络面板里表现为一条莫名其妙的重复请求。
     *
     * <p>只存相对路径（{@code /uploads/...}），与 {@link #avatarUrl} 同口径 ——
     * 换域名或换 IP 时不用批量刷库。
     */
    private String coverUrl;

    /** 通知偏好 JSON:{"disabled":[类型id]},NULL=全开 */
    private String notifyPref;

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

    /** 专业字典 id，指向 school_major.id；层次(本/专科)由 major 派生，不冗余存储 */
    private Long majorId;

    /** 0正常 1冻结 2注销 */
    private Integer status;

    /** 角色:0普通用户 1管理员 */
    private Integer role;

    /** 学校ID,MVP 单校恒为 1,预留多校(migration_v1.sql 第4节) */
    private Long schoolId;

    /** 校区 1东 2西 null未填;与院系解耦独立自选,改不触发重审(2026-10-25) */
    private Integer campus;

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
    /** 加入的圈子数(冗余计数,加圈/退圈事务内 ±1,与 follower_count 同口径) */
    private Integer joinedCircleCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
