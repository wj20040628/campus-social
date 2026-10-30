package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户卡片(脱敏,不暴露 passwordHash / studentNo 等敏感字段)
 *
 * <p><b>为什么需要无参构造</b>：同 PostVO —— {@code @Builder} 不生成无参构造，
 * 而 PostVO 整个对象要存进 Redis 再反序列化回来，嵌套的 UserVO 同样需要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    /**
     * 个人介绍（2026-10-19）。
     *
     * <p>公开字段，null / 空串表示「还没写」，前端据此显示引导文案。
     *
     * <p><b>为什么用它替换原先展示的脱敏学号</b>：学号结构是
     * 「入学年份 + 专业大类 + 专业位 + 班级」，脱敏后 {@code 2308****0678}
     * 仍泄露前三段，足以让人反推并冒充；而介绍是这个产品里
     * 唯一能替代「让陌生人知道你是谁」的安全字段。
     */
    private String bio;
    private String avatarUrl;
    /**
     * 个人主页自定义封面（2026-10-20）。
     *
     * <p>公开字段：封面本来就是给别人看的，{@code of} 与 {@code ofPrivate} 都带。
     *
     * <p>{@code null} = 没设置，前端回落到默认渐变封面。
     */
    private String coverUrl;
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
    /** 校区 1东 2西 null未填(2026-10-25,与院系解耦独立自选,改不触发重审) */
    private Integer campus;
    private Integer followerCount;
    private Integer followingCount;
    /** 该用户发的动态数(按 author_id 现查 post 表,他人主页「动态」数字用) */
    private Integer postCount;
    /**
     * 获赞总数(个人主页「获赞」指标)。
     *
     * <p>由 UserController#card 用 {@code SUM(post.like_count)} 现算填入,
     * 不落 user 表冗余列 —— 冗余列要在点赞/取消/删帖/回填四处维护,
     * 漏一处就永久漂移且难发现。
     */
    private Integer likeCount;
    /**
     * 我收藏的帖子数 / 我赞过的帖子数。
     *
     * <p><b>只在 {@code GET /user/me}(本人)填充</b>,他人主页卡片恒为 null。
     * 这两个是纯私人数据,别人主页不需要也不该拿到。
     * 前端用它显示「收藏 / 赞过」两个 Tab 的数字,避免列表还没加载时先显示 0 再跳变。
     */
    private Integer favoriteCount;
    private Integer likedCount;
    private Integer unreadNotifyCount;
    /** 加入的圈子数(冗余计数,个人主页「圈子」展示用) */
    private Integer joinedCircleCount;
    /**
     * 校园信息是否已登记（verify_status=1）。
     *
     * <p>2026-10-30 起<b>仅表示资料填写状态，不参与任何权限判定</b>，
     * 也不得作为公开身份徽章：学校不提供学生名单，系统无法证明
     * 「某人是某校学生」，把该字段当信用背书属于过度声明。
     * 功能门槛见 {@code CampusAccessService}（账号年龄 + 信誉分）。
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
     * 账号是否已设置密码(仅「我的资料」ofPrivate 暴露)。
     *
     * <p>邮箱验证码自动建号不设密码;前端据此把「修改密码」切换成
     * 「设置密码」,走邮箱验证码而不是原密码。他人卡片不携带此字段。
     */
    private Boolean passwordSet;
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
