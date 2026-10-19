package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 完成引导（POST /onboarding/complete）。
 *
 * <p>一个事务里做完三件事：写学校归属、覆盖写兴趣标签、批量关注推荐用户。
 *
 * <p><b>AC-18 在后端的落点</b>：{@code deptId} 与 {@code gradeId} 都是
 * {@code @NotNull}。前端路由守卫可以被绕过（改 URL、直接调接口），
 * 后端这两个非空校验才是不可绕过的那一道——没填归属就不可能完成引导，
 * 而没归属的用户进了 Feed，推荐算法也没有可用的匹配维度。
 *
 * <p>取值是字典 id 而非名称字符串：AC-14 的同院系匹配依赖它，
 * 自由文本会让「计算机学院」与「计科」成为两个不相等的值。
 *
 * <p>{@code followUserIds} 允许为空：引导第 3 步允许一个都不关注，
 * 「我不想关注任何人」是合法选择而非参数错误。
 */
public class OnboardingCompleteDTO {

    /** 为空时取 campus.school-id（MVP 单校恒为 1） */
    private Long schoolId;

    /** 院系字典 id，取自 GET /dict/depts */
    @NotNull(message = "请选择学院")
    @Positive(message = "学院取值不合法")
    private Long deptId;

    /** 年级字典 id，取自 GET /dict/grades */
    @NotNull(message = "请选择年级")
    @Positive(message = "年级取值不合法")
    private Long gradeId;

    /** 专业字典 id，取自 GET /dict/majors（按学院 + 本专科过滤后的下拉） */
    @NotNull(message = "请选择专业")
    @Positive(message = "专业取值不合法")
    private Long majorId;

    /**
     * 选中的兴趣标签。
     *
     * <p>前端是「每一步都调 complete」的设计(Onboarding §7,防中途中断丢数据):
     * 第 1 步提交时还没有标签,这里必须允许 null/空 —— 形状护栏只管
     * 「给了就最多 8 个」;「至少选几个」的业务校验在 service 层,
     * 且只在 tags 非空时执行(空 = 本步未涉及标签,不覆盖已有标签)。
     *
     * <p>早前这里有 {@code @NotNull + @Size(min=3)},导致第 1 步点「下一步」
     * 就被「兴趣标签需选择 3 到 8 个」拦死,引导根本走不下去(实测截图确认)。
     */
    @Size(max = 12, message = "兴趣标签最多选择 12 个")
    private List<Long> tagIds;

    /** 批量关注的用户 id，可为空 */
    @Size(max = 50, message = "单次最多关注 50 人")
    private List<Long> followUserIds;

    /** 引导第 3 步勾选加入的圈子 id，可为空 */
    @Size(max = 50, message = "单次最多加入 50 个圈子")
    private List<Long> circleIds;

    public Long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(Long schoolId) {
        this.schoolId = schoolId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Long getGradeId() {
        return gradeId;
    }

    public void setGradeId(Long gradeId) {
        this.gradeId = gradeId;
    }

    public Long getMajorId() {
        return majorId;
    }

    public void setMajorId(Long majorId) {
        this.majorId = majorId;
    }

    public List<Long> getTagIds() {
        return tagIds;
    }

    public void setTagIds(List<Long> tagIds) {
        this.tagIds = tagIds;
    }

    public List<Long> getFollowUserIds() {
        return followUserIds;
    }

    public void setFollowUserIds(List<Long> followUserIds) {
        this.followUserIds = followUserIds;
    }

    public List<Long> getCircleIds() {
        return circleIds;
    }

    public void setCircleIds(List<Long> circleIds) {
        this.circleIds = circleIds;
    }
}