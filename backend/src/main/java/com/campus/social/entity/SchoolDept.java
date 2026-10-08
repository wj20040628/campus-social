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
 * 院系字典（F11 引导第 1 步）。
 *
 * <p><b>为什么必须是字典表而不是用户自由文本</b>：AC-14（同院系推荐 / 筛选）依赖
 * {@code dept} 的相等判断。用户 A 填「计算机学院」、用户 B 填「计科」，
 * 落库是两个不同字符串，同院系的人被漏掉一半。自由输入的收敛性无法靠前端约束保证，
 * 因此必须在数据层消灭自由度。
 *
 * <p><b>为什么带 school_id</b>：院系是「学校特有」的设置，同名院系跨校没有可比性
 * （A 校的「外国语学院」与 B 校的同名院系毫无关系）。虽然 MVP 单校恒为 1，
 * 但把 school_id 提前放进字典表，多校时只需按 school_id 过滤即可，
 * 不必回头改主键与唯一键——那是一次需要停机窗口的数据迁移。
 *
 * <p><b>enabled 与 is_deleted 的分工</b>（两者都不是冗余）：
 * {@code enabled=0} 表示「学校撤销或合并了该院系」——历史用户仍持有该 dept_id，
 * 展示时必须仍能解析出名称，否则老用户主页的院系会变成空白；
 * {@code is_deleted} 是 MyBatis-Plus 全局逻辑删除，只用于彻底删干净一条字典记录。
 * 「不允许新用户再选」用 enabled，「这条记录不存在了」用 is_deleted。
 */
@Data
@TableName("school_dept")
public class SchoolDept implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院系名称，如「计算机科学与技术学院」 */
    private String name;

    /** 所属学校ID；MVP 单校恒为 1，与 user.school_id 对齐 */
    private Long schoolId;

    /** 排序权重，升序；步长 10 便于插队。热门院系给小值排前面 */
    private Integer sort;

    /** 1启用 0停用。停用=学校撤销或合并，不再让新用户选，但历史数据仍可解析 */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}