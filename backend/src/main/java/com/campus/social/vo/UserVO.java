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
    private String username;     // 邮箱或手机号(部分脱敏:显示前几位+后几位)
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
    private Integer followerCount;
    private Integer followingCount;
    private Integer unreadNotifyCount;
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
    private LocalDateTime createTime;
}
