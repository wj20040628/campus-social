package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 学校字典响应（openapi.yaml SchoolDictResponse 的 data）。
 *
 * <p><b>为什么院系与年级合并成一个端点</b>：引导第 1 步的三个下拉要同时可用，
 * 分两次请求会出现「学院已选、年级还在转圈」的中间态——用户可能在年级未加载时
 * 就点了下一步。合并后一次往返即可，且客户端进程内缓存一次就够
 * （字典总量 60 余条、TTL 10min，不值得为它做两套缓存键）。
 *
 * <p>结构与 {@code TagTreeVO} 同风格：裸 data 对象 + 列表 + 计数，不额外套层级。
 *
 * <p>注意语义不对称：{@code depts} 按 {@code schoolId} 过滤，
 * {@code grades} 不带学校维度（2024 级在任何学校都是 2024 级）。
 * 合并返回只是为了一次往返，不代表年级是学校子集。
 *
 * <p>无参构造不能省：本类会被 JSON 序列化后存进 Redis，反序列化需要它。
 * {@code @Builder} 会抑制 Lombok 生成无参构造，只写 @Data + @Builder 会让读缓存
 * 抛 JsonMappingException，而缓存读失败被降级成查库——结果是缓存永不命中且无任何报错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchoolDictVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 本次院系列表所属的学校 id */
    private Long schoolId;

    /** 启用中的院系，按 sort 升序、id 升序兜底 */
    private List<DeptItemVO> depts;

    /** 启用中的年级，按 sort 升序（新近入学的排最前） */
    private List<GradeItemVO> grades;

    /** 可选院系总数，前端显示「共 N 个院系」 */
    private Integer deptTotal;

    /** 可选年级总数，不含已毕业年级 */
    private Integer gradeTotal;
}