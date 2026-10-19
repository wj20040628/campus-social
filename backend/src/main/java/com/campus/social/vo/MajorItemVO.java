package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 专业字典项（GET /dict/majors 的列表元素）。
 *
 * <p>含 {@code code} 与 {@code level}：前端做学号专业码即时提示、按层次分组时可能用到；
 * 名称快照(id + name)与 dept/grade 字典项一致，前端拿 id 落库、拿 name 展示。
 *
 * <p>无参构造不能省：本类若进 Redis 缓存反序列化需要它（与 DeptItemVO 同款约束，
 * 否则读缓存抛 JsonMappingException 被降级成查库，缓存永不命中且无报错）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MajorItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属学院 id;前端按「学院 + 本专科」过滤专业时用 */
    private Long deptId;

    private String name;

    /** 6 位校内专业编码，如 080901 */
    private String code;

    /** 层次：1=本科 2=专科 */
    private Integer level;

    /** 排序权重 */
    private Integer sort;
}
