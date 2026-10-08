package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 学校归属回执（F2 引导第 1 步）。
 *
 * <p><b>为什么同时回传 id 与名称</b>：前端拿到名称可以直接显示，
 * 拿到 id 可以存进本地草稿、下次进引导页时回填选中项——
 * 只回名称的话前端得再发一次请求反查名称对应的 id。
 *
 * <p>{@code profileComplete} 是 AC-18 的判定依据：deptId 与 gradeId 均非空。
 * 前端据此阻断进入 Feed——归属不全的用户进了 Feed，推荐算法没有可用的匹配维度。
 */
@Data
@Builder
public class SchoolProfileVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long schoolId;

    private String schoolName;

    /** 院系字典 id */
    private Long deptId;

    /** 院系名称，由服务端查字典得到，前端不必二次请求 */
    private String deptName;

    /** 年级字典 id */
    private Long gradeId;

    /** 年级名称，如「2024级」 */
    private String gradeName;

    /** deptId 与 gradeId 均非空 */
    private Boolean profileComplete;
}