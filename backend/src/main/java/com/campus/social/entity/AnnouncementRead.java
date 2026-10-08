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
 * 公告已读回执(F5)。
 *
 * <p>{@code uk_ann_user(announcement_id, user_id)} 是 AC-09 幂等的数据库兜底：
 * 重复上报走 INSERT IGNORE 不新增行。
 *
 * <p>只在实际有人读时写一行；未读数用 count 查询而非全量写入。
 */
@Data
@TableName("announcement_read")
public class AnnouncementRead implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long announcementId;

    private Long userId;

    private LocalDateTime readTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
