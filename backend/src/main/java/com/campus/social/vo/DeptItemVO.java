package com.campus.social.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 院系字典项（openapi.yaml DeptItem）。
 *
 * <p>与 {@code TagItemVO} 分开成文件而非复用：字段不同（字典项无 use_count，
 * 而 use_count 是兴趣标签特有的计数），复用一个类会让两个契约互相污染。
 *
 * <p><b>无参构造不能省</b>：这个类会被 JSON 序列化后存进 Redis，
 * 反序列化时 Jackson 需要无参构造。{@code @Builder} 会抑制 Lombok 生成无参构造，
 * 只写 @Data + @Builder 会导致读缓存时抛 JsonMappingException，
 * 而缓存读失败被降级成查库——结果是缓存永远不命中且没有任何报错，
 * 属于最难发现的一类沉默故障。故显式补齐 @NoArgsConstructor + @AllArgsConstructor。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeptItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 排序权重，前端本地排序时用得到 */
    private Integer sort;
}