package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户卡片(脱敏,不暴露 passwordHash / studentNo 等敏感字段)
 */
@Data
@Builder
public class UserVO {
    private Long id;
    /**
     * 登录主账号(部分脱敏:学号 13/14 位 → 2308****0678;旧账号的邮箱/手机走原脱敏规则)。
     *
     * <p>重构后 username 内部存学号(账号体系重构 2026-10-17),脱敏形态见
     * {@code UserVoAssembler.maskUsername}。此处只存脱敏展示值,原始值永不外发。
     */
    private String username;
    private String nickname;
    private String avatarUrl;
    private String grade;
    private String dept;
    /**
     * 院系 / 年级字典 id，与上面的名称快照并存。
     *
     * <p>为什么 VO 里 id 和名称都要有：名称供展示，id 供前端判断「这个值是不是
     * 字典里的正式项」。字典化之前注册的用户 id 为 NULL 但名称有值，
     * 前端若用 {@code deptId === null} 判定「用户没填院系」就会把这些人
     * 错误地推进引导页。正确判据是 id 与名称同时为空。
     */
    private Long deptId;
    private Long gradeId;
    /** 专业字典 id（学院→专业级联落库，层次由 major.level 派生，不冗余存专业名） */
    private Long majorId;
    private Integer followerCount;
    private Integer followingCount;
    /** 该用户发的动态数(按 author_id 现查 post 表,他人主页「动态」数字用) */
    private Integer postCount;
    private Integer unreadNotifyCount;
    /** 加入的圈子数(冗余计数,个人主页「圈子」展示用) */
    private Integer joinedCircleCount;
    /**
     * 是否已通过校内认证（AC-12 徽章判据）。
     *
     * <p><b>为什么用 {@code Boolean} 而不是 {@code int verifyStatus}</b>：
     * AC-12 只要求「已认证 / 未认证」两态，前端徽章只需一个布尔；
     * 而四态的审核中 / 被拒语义属于「我的认证进度」，由
     * {@code GET /user/verify/status} 返回的 {@code VerifyStatusVO} 承担。
     * 在 UserVO 里再塞一份四态字段，两处一旦口径漂移就会让徽章显示错误状态。
     *
     * <p><b>为什么必须由服务端给出</b>：前端此前用
     * 「是否填了院系 / 年级」反推认证状态，而那与认证是两个独立事实——
     * 用户可以在引导第 1 步填完院系却从未提交邮箱验证码。
     * 那种情况下前端反推会显示「已认证」，是伪造的身份声明。
     *
     * <p>兼容：老数据或组装路径未显式设置时为 null，前端按未认证处理。
     */
    private Boolean verified;
    /** 0普通用户 1管理员 */
    private Integer role;
    /**
     * 是否已绑定安全凭证邮箱(仅「我的资料」ofPrivate 暴露)。
     *
     * <p>前端据此决定「绑定邮箱 / 修改邮箱」入口的显隐,以及是否开放
     * 邮箱验证码登录与自助找回密码。他人卡片( {@code of})不携带此字段。
     */
    private Boolean emailBound;
    /**
     * 已绑定的脱敏邮箱(仅「我的资料」ofPrivate 暴露,如 {@code ze***n@campus.test})。
     *
     * <p><b>为什么只给脱敏值</b>:邮箱是安全凭证,即使本人也不该在任意响应里拿到
     * 完整值——完整值只在「验证邮箱所有权」(发码/回填)链路使用,从不在读接口回显。
     * 他人卡片( {@code of})不携带此字段。
     */
    private String email;
    /**
     * 是否允许陌生人给自己发消息（AC-05 护栏开关）。
     *
     * <p>只在「我的资料」(ofPrivate) 暴露：这是隐私偏好，
     * 不应出现在他人卡片上。默认 1（开启）。
     */
    private Boolean strangerMsgEnabled;
    private LocalDateTime createTime;
}
