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
 * 兴趣标签字典(F11 引导第 2 步)。
 * 人工维护,不做 UGC——避免脏标签与审核成本。
 */
@Data
@TableName("interest_tag")
public class InterestTag implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 1兴趣 2游戏 3运动 4学习 5生活 */
    private Integer category;

    /** 组内排序,升序 */
    private Integer sort;

    /** 被多少用户选中;增减一律 SET use_count = use_count ± 1,绝不读改写 */
    private Integer useCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
