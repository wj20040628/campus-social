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
 * 专业字典（学院下属专业，支撑注册「学院 → 专业」二级联动下拉与学号专业码校验）。
 *
 * <p><b>层次(level)放在专业而不是学院</b>：一个学院同时有本科和专科专业，
 * 层次是专业的固有属性。级联下拉按「学院 + 本专科」过滤，因此索引含 level。
 *
 * <p><b>code 用 CHAR(6) 定长</b>：学号专业码定长 6 位、可含前导 0（如 080901），
 * 定长比较不做尾部空格处理，与定长编码语义一致（见 migration_f12_school_major.sql）。
 *
 * <p><b>enabled 与 is_deleted 分工</b>同 school_dept：enabled=0 表示专业停招，
 * 不再让新用户选，但历史用户仍可解析；is_deleted 是 MP 全局逻辑删除。
 */
@Data
@TableName("school_major")
public class SchoolMajor implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属学院 id，指向 school_dept.id；学院 → 专业级联的父键 */
    private Long deptId;

    /** 所属学校 id；MVP 单校恒为 1，与 school_dept.school_id 对齐 */
    private Long schoolId;

    /** 专业名称，如「计算机科学与技术」 */
    private String name;

    /** 6 位校内专业编码，对应学号位 3-8（大类 4 位 + 专业位号 2 位），定长可含前导 0 */
    private String code;

    /** 层次：1=本科 2=专科，对应学号层次标记（本科 05/5、专科 06/6） */
    private Integer level;

    /** 专业码类型：1=本科国标码 2=专科校内实锤码 3=高职占位码 */
    private Integer codeType;

    /** 排序权重，升序；步长 10 便于插队 */
    private Integer sort;

    /** 1启用 0停用 */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
