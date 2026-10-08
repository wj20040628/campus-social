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
 * 官方公告(F5)。
 *
 * <p><b>为什么不用 notify 表达</b>：notify 是用户级事件（每条属于一个 user），
 * 公告是广播级内容。用 notify 会造成写入放大——1 万用户 × 100 公告 = 100 万行，
 * 且已读状态更新要 UPDATE 1 万行。SPEC §6.3 明确禁止这个做法。
 */
@Data
@TableName("announcement")
public class Announcement implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 正文,纯文本+换行,MVP 不做富文本 */
    private String content;

    /** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
    private Integer category;

    private Long publisherId;

    /** MVP 单校恒为 1,预留多校 */
    private Long schoolId;

    /** 0否 1是。置顶且在 topExpireTime 之前才生效 */
    private Integer isTop;

    /** 置顶到期时间,到期后自然回落 */
    private LocalDateTime topExpireTime;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    private LocalDateTime publishTime;

    /** 已读人数,仅首次已读时 +1 */
    private Integer readCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
