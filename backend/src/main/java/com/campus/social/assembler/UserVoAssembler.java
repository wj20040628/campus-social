package com.campus.social.assembler;

import com.campus.social.entity.User;
import com.campus.social.vo.UserVO;

/**
 * UserVO 唯一装配出口。
 *
 * <p><b>为什么必须收口</b>：本类创建前，全项目有 <b>14 处</b> {@code UserVO.builder()}
 * 各自手写字段。它们看上去只是"重复代码"，实际已经造成两处真实事故：
 *
 * <ol>
 *   <li><b>隐私泄露（已修）</b>：14 处里只有 {@code AuthServiceImpl.toVo} 一处调用了
 *       {@link #maskUsername}，其余 13 处直接写 {@code .username(u.getUsername())}
 *       吐原值。而 {@code UserVO.username} 的字段注释与 openapi 契约
 *       （{@code docs/api/openapi.yaml} UserVO.username）都明写"部分脱敏"。
 *       后果：任何人只要能加载 Feed、评论区、通知、私聊，就能拿到全校师生的
 *       完整邮箱与手机号。字段注释写了不等于做了——脱敏逻辑只存在于一份实现里，
 *       剩下 13 份都当它不存在。</li>
 *   <li><b>AC-12 徽章口径漂移（已修）</b>：{@code verified} 曾只有 1/14 处填写，
 *       最严重的是 {@code PostVoAssembler}——它装配所有 Feed 与帖子详情的作者卡，
 *       导致已认证用户的帖子在界面上显示"未认证"。</li>
 * </ol>
 *
 * <p>两个出口的差别只在<b>私密字段</b>，不在公开字段：
 * <ul>
 *   <li>{@link #of} —— 他人卡片。<b>不含 role</b>：角色是权限判据，
 *       出现在他人卡片上没有意义，且会泄露"谁是管理员"这个不该公开的信息。</li>
 *   <li>{@link #ofPrivate} —— 仅供 {@code GET /user/me} 与
 *       {@code PUT /user/profile}（即 {@code AuthServiceImpl.me/updateProfile}）。
 *       含 role 与未读通知数。</li>
 * </ul>
 *
 * <p><b>为什么 role 不并入 of</b>：甲方案（一个方法全给）会把 role 扩散到
 * Feed、评论区、私聊等每一个 UserVO，与 AC-12「不泄露身份信息」的精神相悖。
 * 「我的资料」是唯一有理由知道自己角色的地方。
 *
 * <p><b>为什么 username 一律脱敏、连自己的也脱敏</b>：{@code /user/me} 的调用方
 * 手里已经有自己的 accessToken，登录时用过完整 username，再回传一次全量值
 * 没有任何信息增益，却让"脱敏"这条规则出现例外分支——例外分支就是下一次
 * 漏改的地方。规则统一比"给主人开后门"重要。
 */
public final class UserVoAssembler {

    private UserVoAssembler() {
    }

    /**
     * 他人卡片装配。<b>不含 role / unreadNotifyCount</b>。
     *
     * <p>user 为 null 时返回 null，让调用方决定是 filter 掉还是填占位——
     * 装配器不替业务做"用户不存在"的判断。
     */
    public static UserVO of(User user) {
        if (user == null) {
            return null;
        }
        return base(user)
                .followerCount(user.getFollowerCount())
                .followingCount(user.getFollowingCount())
                .build();
    }

    /**
     * 「我的资料」装配，比 {@link #of} 多 role 与未读通知数。
     *
     * <p><b>仅限 {@code GET /user/me} 与 {@code PUT /user/profile} 使用。</b>
     * 任何列表、详情、评论区、私聊、通知里的用户卡片都应走 {@link #of}——
     * 判断标准很简单：<b>这个响应里出现的是「我」吗？</b>
     */
    public static UserVO ofPrivate(User user) {
        if (user == null) {
            return null;
        }
        return base(user)
                .role(user.getRole() == null ? 0 : user.getRole())
                .unreadNotifyCount(user.getUnreadNotifyCount())
                .followerCount(user.getFollowerCount())
                .followingCount(user.getFollowingCount())
                .strangerMsgEnabled(user.getStrangerMsgEnabled() == null
                        || user.getStrangerMsgEnabled() == 1)
                // 安全凭证邮箱:仅「我的资料」暴露脱敏值。他人卡片( of )不含此字段,
                // 避免邮箱(安全凭证)出现在 Feed / 评论区 / 私聊等任何公开响应里。
                .email(user.getEmail() == null ? null : maskUsername(user.getEmail()))
                .build();
    }

    /**
     * 公开字段的唯一装配逻辑。{@link #of} 与 {@link #ofPrivate} 都走这里，
     * 保证公开字段在任何路径下都不会出现口径差异。
     *
     * <p>nickname 为 null 时退化到 username：注册允许不填昵称，
     * 而昵称是所有卡片的必显字段，直接返回 null 会让前端到处写兜底。
     * 注意退化用的是<b>脱敏后</b>的 username，不是原值——否则昵称为空的用户
     * 反而成了唯一泄露原值的人。
     */
    private static UserVO.UserVOBuilder base(User user) {
        String masked = maskUsername(user.getUsername());
        return UserVO.builder()
                .id(user.getId())
                .username(masked)
                .nickname(user.getNickname() != null ? user.getNickname() : masked)
                /**
                 * 个人介绍（2026-10-19）：放 base 让 of / ofPrivate 都带。
                 *
                 * <p>公开字段、无敏感信息：它取代了原先展示脱敏学号的位置
                 * （学号结构会泄露入学年份与专业大类，见 §3.3 的安全修正）。
                 *
                 * <p>null 表示「还没写」而非「写了空的」，前端据此显示引导文案。
                 */
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                /**
                 * 主页封面（2026-10-20）。公开字段：封面是给别人看的第一眼印象，
                 * 放在 base 让 of / ofPrivate 都带 —— 若只给本人，
                 * 访客看你的主页就永远是那张默认渐变，等于这个功能白做。
                 */
                .coverUrl(user.getCoverUrl())
                .grade(user.getGrade())
                .dept(user.getDept())
                // 字典 id 与名称快照并存：前端要靠它区分「字典里的正式项」与
                // 「字典化之前填的老数据」。只给名称的话前端无法判断该不该推进引导页。
                .deptId(user.getDeptId())
                .gradeId(user.getGradeId())
                .majorId(user.getMajorId())
                // AC-12 徽章判据。必须由服务端给——前端从「是否填了院系」反推
                // 是在伪造身份声明，用户可以在填完院系后从未提交过邮箱验证码。
                .verified(Integer.valueOf(1).equals(user.getVerifyStatus()))
                .joinedCircleCount(user.getJoinedCircleCount())
                // 是否已绑定安全凭证邮箱(学号主账号体系)。仅「我的资料」消费,
                // 公开卡片不依赖此值,故放 base 让 of / ofPrivate 都带上也不泄露隐私
                // (他人看不到 emailBound 之外的邮箱信息,true/false 本身无敏感)。
                .emailBound(user.getEmail() != null)
                .createTime(user.getCreateTime());
    }

    /**
     * 用户名部分脱敏：邮箱 {@code zexin@campus.test} → {@code ze***n@campus.test}，
     * 手机号 {@code 13812345678} → {@code 138****5678}。
     *
     * <p><b>本项目全站唯一的脱敏实现。</b>改这里等于改全站 14 条用户卡片路径的
     * 可见字段，往返请先想清楚 {@code GET /user/me} 之外的响应有没有依赖全量值。
     */
    public static String maskUsername(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        int at = raw.indexOf('@');
        if (at > 0) {
            String local = raw.substring(0, at);
            String domain = raw.substring(at + 1);
            return local.length() <= 2 ? local + "***@" + domain
                    : local.substring(0, 2) + "***" + local.substring(local.length() - 1) + "@" + domain;
        }
        // 学号(主账号,账号体系重构 2026-10-17):13 / 14 位纯数字。
        // 形如 230820230100678 → 2308****0678(首尾各留 4 位,中间 4 位打码)。
        // 放手机号分支之前:学号长度与 11 位手机号不重叠,先判学号不会误伤手机。
        if (raw.length() >= 13 && raw.length() <= 14
                && raw.chars().allMatch(Character::isDigit)) {
            return raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);
        }
        // 纯手机号：11 位。不按长度硬判 1x 开头，测试数据与境外号码不一定是这个形状
        if (raw.length() == 11 && raw.chars().allMatch(Character::isDigit)) {
            return raw.substring(0, 3) + "****" + raw.substring(7);
        }
        // 既不是邮箱也不是 11 位数字：没有可脱敏的形态，原样返回而非截断——
        // 截断一个真实值比不脱敏更难排查
        return raw;
    }
}
