package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 年级字典项（openapi.yaml GradeItem）。
 *
 * <p>{@code enrollYear} 回传给前端而非只给名称：引导页可能要按年级做分组
 * 或高亮「2024级」，让前端从字符串里截前 4 位等于把解析规则复制一份到两端。
 *
 * <p>无参构造的必要性见 {@link DeptItemVO} 的同名说明（Redis 反序列化需要）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 入学年份，如 2024 */
    private Integer enrollYear;

    private Integer sort;
}