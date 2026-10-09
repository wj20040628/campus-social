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
 * <p>字段刻意精简到 6 个：OD-19 要求后台在 375px 视口的手机上可用，
 * 而管理员判断真伪真正需要的只有「学号 + 院系年级 + 昵称」——
 * 头像、简介、粉丝数这些东西塞进来只会让卡片撑到要横向滚动。
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