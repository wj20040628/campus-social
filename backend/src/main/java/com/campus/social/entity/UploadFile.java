package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 上传文件归属表。
 *
 * <p>记录「哪个用户上传了哪个 url」,用于头像归属校验:
 * {@code user.avatar_url} / {@code group.avatar_url} 引用上传目录里的文件时,
 * 必须确认该文件确实由当前用户(群主)上传,否则拒绝——
 * 堵住「任意登录用户引用他人上传文件 URL 当自己头像」的越权。
 *
 * <p>兼容策略:老数据 / 外部图床 URL 没有归属记录,校验方只对
 * 「明确属于别人的文件」拒绝,对「查无归属记录」放行(不断老数据)。
 */
@Data
@TableName("upload_file")
public class UploadFile implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传者 id(user.id)。群头像归属到发起建群/改头像的成员。 */
    private Long userId;

    /** 上传落盘后的相对 URL,如 /uploads/20261013/xxx.mp4 */
    private String url;

    /** image | video */
    private String kind;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
