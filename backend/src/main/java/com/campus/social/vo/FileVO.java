package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件库条目（列表 / 详情 / 管理端共用）。
 *
 * <p>不暴露 objectKey：下载统一走后端签名 URL 或本地流，
 * 前端只需要文件 id。公开分享页会把这个 VO 里的「我的状态」字段置 false、
 * 驳回理由置空，其余字段可公开。
 */
@Data
public class FileVO {

    private Long id;

    private String title;

    private String description;

    /** 分类 code，文案由前端 constants/fileCategories.js 映射 */
    private String category;

    /** pdf/docx/pptx/xlsx/... */
    private String ext;

    /** 原始文件名 */
    private String fileName;

    private Long sizeBytes;

    private Long uploaderId;

    private String uploaderNickname;

    private String uploaderAvatar;

    /** 上传者是否已认证 */
    private Boolean uploaderVerified;

    /** 0待审 1通过 2驳回 3下架；公开页恒为 1 */
    private Integer status;

    /** 驳回/下架理由；公开页为 null */
    private String rejectReason;

    private LocalDateTime reviewTime;

    private Integer viewCount;

    private Integer downloadCount;

    private Integer likeCount;

    private Integer favoriteCount;

    /** 当前登录人是否已点赞；游客恒 false */
    private Boolean myLike;

    /** 当前登录人是否已收藏；游客恒 false */
    private Boolean myFavorite;

    /** 当前登录人是否可删除（仅上传者） */
    private Boolean canDelete;

    private LocalDateTime createTime;
}
