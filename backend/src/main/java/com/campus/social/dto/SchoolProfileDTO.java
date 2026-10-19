package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 设置学校院系与年级（F2 引导第 1 步）。
 *
 * <p><b>为什么从自由文本改成字典 id</b>：AC-14（同院系推荐 / 筛选）依赖院系相等判断。
 * 用户 A 填「计算机学院」、B 填「计科」，落库是两个不同字符串，同院系的人被漏掉一半。
 * 自由输入的收敛性无法靠前端约束保证，必须在数据层消灭自由度。
 *
 * <p>id 传错（不存在 / 已停用 / 不属于本校）由 service 查库判定，回 422 {@code DEPT_INVALID}；
 * DTO 这层只管「必须传且是正整数」，属400 形状校验。两层职责不重叠。
 *
 * <p>{@code schoolId} 仍不接受任意值：MVP 单校，传别的值会被 service 拒绝
 * （见 CampusVerifyServiceImpl#saveSchoolProfile）。
 */
@Data
public class SchoolProfileDTO {

    /** MVP 单校恒为 1；不传按配置默认值处理 */
    private Long schoolId;

    /** 院系字典 id，取自 {@code GET /dict/depts} */
    @NotNull(message = "请选择学院")
    @Positive(message = "学院取值不合法")
    private Long deptId;

    /** 年级字典 id，取自 {@code GET /dict/grades} */
    @NotNull(message = "请选择年级")
    @Positive(message = "年级取值不合法")
    private Long gradeId;

    /** 专业字典 id，取自 {@code GET /dict/majors}（按学院 + 本专科过滤后的下拉） */
    @NotNull(message = "请选择专业")
    @Positive(message = "专业取值不合法")
    private Long majorId;
}