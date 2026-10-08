package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 年级字典（F11 引导第 1 步）。
 *
 * <p><b>为什么建表而不是写成 Java 常量枚举</b>（这个决策被反复追问，故理由写全）：
 * <ol>
 *   <li><b>每年都要新增，改枚举要发版</b>。年级是随时间增长的集合（每招生季新增一级），
 *       把它写进代码意味着「每年九月发一次版」只为加一行数据。写进表里是 INSERT。</li>
 *   <li><b>毕业年级需要「退役但仍可解析」</b>。2020 级毕业了，但两年前注册的老用户
 *       资料里仍写着 2020 级。Java 枚举没有「已停用但仍存在」的第三态——
 *       删掉常量会让老用户的年级变成空白，保留常量又会让新用户能选到已毕业年级。
 *       {@code enabled} 正好是这个第三态。</li>
 *   <li><b>一致性</b>。与 dept 共用同一套校验、同一个错误码、同一个缓存路径，
 *       前端只需要学会一种下拉框的数据结构。</li>
 * </ol>
 *
 * <p><b>为什么不带 school_id</b>：年级是「入学年份」，2024 级在任何学校都是 2024 级，
 * 跨校同年级天然可比（同届），语义上与「院系」不同。院系带 school_id、年级不带，
 * 这个不对称是有意的，不是遗漏。
 */
@Data
@TableName("school_grade")
public class SchoolGrade implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 年级名称，如「2024级」 */
    private String name;

    /** 入学年份，如 2024。用于排序与「当前可选范围」的判断，不用于展示 */
    private Integer enrollYear;

    /** 排序权重，升序。新生级给小值排前面（最近入学的人在引导页排最上） */
    private Integer sort;

    /** 1启用 0停用。毕业年级置 0：不再让新用户选，历史数据仍可解析 */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}