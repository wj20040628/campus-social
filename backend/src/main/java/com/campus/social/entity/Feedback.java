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
 * 意见反馈(2026-10-18,导航抽屉「意见反馈」)。
 *
 * <p><b>为什么不复用 report</b>:report 的语义是「对某个具体对象的违规举报」,
 * 有 {@code target_type}/{@code target_id} 多态指向,且带 {@code uk_report}
 * 幂等键(同一人对同一目标只能一条)。意见反馈是对产品本身的建议 / 问题报告,
 * 没有举报对象,一个人可以提很多条 —— 塞进 report 只能把 target_type 填 0,
 * 既污染举报列表,又会让 AC-13 的幂等约束误伤(第二次反馈被当成「重复举报」拒掉)。
 *
 * <p><b>mediaJson 为什么存字符串而不是关联表</b>:结构与发帖的媒体完全同形
 * ({@code [{type,url}]}),且生命周期只跟反馈走,不存在「被别处引用」。
 * 单独建关联表要多一次 JOIN 与一个删除级联,换不来任何东西。
 *
 * <p>{@code status} 三态与 report 一致:0 待处理 / 1 已采纳 / 2 已忽略。
 * 不设「已修复」——产品改没改只有开发者知道,让管理员勾「已采纳」即可。
 */
@Data
@TableName("feedback")
public class Feedback implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1功能建议 2使用问题 3内容问题 4界面体验 5其他 */
    private Integer category;

    /** 文字描述。DTO 侧限 1000 字,与列宽一致 */
    private String content;

    /** {@code [{"type":"img","url":"..."}]}。空表示没传图 */
    private String mediaJson;

    /** 0待处理 1已采纳 2已忽略 */
    private Integer status;

    private Long handlerId;

    private LocalDateTime handleTime;

    private String handleNote;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
