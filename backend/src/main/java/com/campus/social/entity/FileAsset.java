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
 * 文件库条目。
 *
 * <p><b>status 自己就是审核队列</b>：{@code review_task} 的 {@code post_id NOT NULL}
 * 与唯一键把它锁死在帖子域，文件不能硬塞。这里用 {@code status=0} 当待审列表，
 * 管理员处置走条件 UPDATE {@code WHERE status=0}，不会出现「队列表说通过、
 * 文件表还是待审」的双写漂移。
 *
 * <p><b>object_key 不对外下发</b>：下载统一走后端签名 URL / 本地流，
 * 前端拿到的只有文件 id，避免私有桶对象路径被直接拼出来。
 */
@Data
@TableName("file_asset")
public class FileAsset implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传者 user.id */
    private Long userId;

    private String title;

    private String description;

    /** 分类编码，取值见 util/FileCategory */
    private String category;

    /** 原始文件名，仅展示与下载响应头用 */
    private String fileName;

    /** 存储对象 key：files/{userId}/{yyyyMMdd}/{uuid}.{ext} */
    private String objectKey;

    private String ext;

    private String mimeType;

    private Long sizeBytes;

    /** 0待审 1通过 2驳回 3下架 */
    private Integer status;

    private String rejectReason;

    private Long reviewerId;

    private LocalDateTime reviewTime;

    private Integer viewCount;

    private Integer downloadCount;

    private Integer likeCount;

    private Integer favoriteCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
