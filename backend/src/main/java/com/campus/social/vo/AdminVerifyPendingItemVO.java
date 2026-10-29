package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条待审的学号核验（openapi AdminVerifyPendingItem）。
 *
 * <p>字段精简到「管理员判断真伪真正需要的」：学号、院系、年级、昵称，
 * 加上 2026-10-28 补的「专业 / 本专科 / 与学号的比对结果」。
 * OD-19 要求后台在 375px 手机上可用，头像、简介、粉丝数一律不下发——
 * 塞进来只会让卡片撑到要横向滚动。
 *
 * <p>比对结果由 service 用**字典值**算（专业 code、年级 enroll_year），
 * 不做「'2023级' 字符串匹配」这种猜测：字典缺行或学号解析不出时回 null，
 * 前端只在 false 时标红，null 不标——「不知道」不能被显示成「不一致」。
 *
 * <p>{@code dept} / {@code grade} 取的是名称快照列：字典化之前注册的老用户
 * 只有名称没有 id，直接下发 id 会让管理员在手机上看到一串数字而非「计算机学院」。
 *
 * <p><b>必须有无参构造</b>：本 VO 由 {@code UserMapper.selectPendingVerify} 的
 * 注解 SQL 直接映射填充，MyBatis 反射实例化时找的是无参构造 + setter。
 * 只写 {@code @Builder} 会让 Lombok 生成全参构造并吃掉隐式无参构造，
 * 结果是编译期完全正常、运行期MyBatis 抛「no default constructor」——
 * 而这条 SQL 只在管理员打开审核页时才跑到，冒烟若不点开这个页面就漏过去。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminVerifyPendingItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 申请人 user.id，approve / reject 端点的路径参数就是它 */
    private Long userId;

    private String nickname;

    /** 申请人填的学号。UNIQUE，同号已被别的账号占用时提交即 409 */
    private String studentNo;

    /** 院系名称快照 */
    private String dept;

    /** 年级名称快照 */
    private String grade;

    /** 专业名称（字典 school_major.name）；字典缺行时为 null */
    private String major;

    /** 字典层次原值：1=本科 2=专科；service 转成 {@link #level} 文案 */
    private Integer majorLevel;

    /** 本专科文案：本科 / 专科；字典缺行时为 null */
    private String level;

    /** 字典里该专业的 6 位专业码，与学号位 3-8 对比用；仅展示与排查用 */
    private String majorCode;

    /** 字典里该年级的入学年份，与学号前两位 +1900/2000 对比用 */
    private Integer gradeEnrollYear;

    /**
     * 年级是否与学号一致。true=一致 / false=不一致 / null=无法判断
     * （学号解析失败或字典缺行）。前端只在 false 时标红。
     */
    private Boolean gradeMatch;

    /** 专业是否与学号一致。取值语义同 {@link #gradeMatch} */
    private Boolean majorMatch;

    /**
     * 学院是否与所选专业一致（2026-10-28 补）。
     *
     * <p>学号里没有学院：学院是「专业 → 所属学院」推出来的，
     * 所以这一项比的是 {@code user.dept_id} 与 {@code school_major.dept_id}。
     * 正常走级联下拉选出来的必然一致；不一致说明数据不是从下拉来的
     * （老数据 / 直接调接口提交），正是管理员要拦的那类。
     * 任一侧为 null 时为 null，不标红。
     */
    private Boolean deptMatch;

    /** 提交时间（user.update_time），同时也是列表的倒序排序键 */
    private LocalDateTime submittedAt;

    /**
     * 学号前10 位拆出来的入学年份/专业/班级，纯展示。
     *
     * <p><b>不来自数据库</b>：由 {@code StudentNoParser} 在 service 层现算，
     * 因此本字段与 {@code user} 表无对应列，mapper 不填它。
     * 不进契约 required——解析规则尚未在全校学号上验证完，
     * 前端必须能handle 它缺失。
     */
    private StudentNoParsedVO studentNoParsed;
}