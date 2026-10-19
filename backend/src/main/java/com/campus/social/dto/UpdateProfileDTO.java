package com.campus.social.dto;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

/**
 * 更新个人资料（PUT /user/me）。
 *
 * <p><b>{@code dept} 与 {@code grade} 已从本 DTO 移除</b>。
 *
 * <p>原因：这两个字段原先是自由文本，与字典化的目标直接冲突。若保留，
 * 用户可以通过本接口把 {@code user.dept} 写成任意字符串而 {@code dept_id} 不变，
 * 结果是「展示名称与匹配 id 不一致」——正是本轮字典化要消灭的那类脏数据。
 *
 * <p>归属信息（院系 / 年级）改由两个专用入口写入，语义更清晰也更容易加校验：
 * 引导第 1 步走 {@code POST /user/school-profile}，引导整体完成走
 * {@code POST /onboarding/complete}。两者都按字典 id 落库并校验启用状态。
 *
 * <p><b>为什么已下线字段要显式报错而不是静默忽略</b>：Jackson 默认丢弃
 * 未知字段，若不做处理，调用方传 {@code dept} 会收到 200 + 正常响应，
 * 但数据一个字节都没落库。这比直接报错难查得多——前端会以为改成功了，
 * 用户刷新页面发现院系没变，却没有任何线索指向是哪个接口吞了它。
 * 所以用 {@link JsonAnySetter} 拦下所有未声明字段，命中已下线名单时报错，
 * 其余未知字段仍按原样放过，避免把无关字段也变成硬失败。
 */
@Data
public class UpdateProfileDTO {

    /**
     * 已下线的字段名单。放在这里而不是散落在校验逻辑里，是因为它同时是
     * 「给调用方的错误提示」和「防止后人把字段加回来的说明」。
     */
    private static final Set<String> RETIRED_FIELDS = Set.of("dept", "grade");

    @Size(max = 64)
    private String nickname;

    /**
     * 个人介绍（2026-10-19）。
     *
     * <p><b>必须在本 DTO 里声明</b>：下面 {@link #rejectRetiredField} 用
     * {@code JsonAnySetter} 兜底，未声明字段会被<b>静默忽略</b> ——
     * 前端传了 bio 收到 200，数据却一字节没落库，比直接报错难查得多。
     *
     * <p>长度与 {@code user.bio} 列宽一致（200）。
     */
    @Size(max = 200, message = "介绍最多 200 字")
    private String bio;

    @Size(max = 512)
    private String avatarUrl;

    @Size(max = 32)
    private String studentNo;

    /**
     * 兜底拦截未声明字段。只对已下线名单报错，其余字段交给上层按原行为处理。
     *
     * <p>注意这里必须用 {@code JsonAnySetter} 而不是 {@code @JsonIgnoreProperties}：
     * 后者只能整体忽略或整体报错，无法做到「放行无关字段、只拦已下线字段」。
     */
    @JsonAnySetter
    public void rejectRetiredField(String fieldName, Object value) {
        if (RETIRED_FIELDS.contains(fieldName)) {
            throw new BusinessException(ResultCode.PROFILE_FIELD_RETIRED);
        }
        // 其余未知字段不在本 DTO 的职责范围内，忽略即可。
        // 此处不记日志：未知字段通常来自客户端版本差异，量大且无害，
        // 逐条打日志会淹没真正的错误
    }
}
