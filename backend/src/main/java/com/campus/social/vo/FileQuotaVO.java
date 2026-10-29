package com.campus.social.vo;

import lombok.Data;

/**
 * 文件上传额度与当前档位（GET /files/quota）。
 *
 * <p>只给用户看自己的额度，不给公式细节；分数与档位来自 ReviewTrustService，
 * 单文件/总量来自 FileLimits，两边不会各算一套。
 */
@Data
public class FileQuotaVO {

    private Integer score;

    private Boolean verified;

    /** 0=不可上传 1/2/3=档位 */
    private Integer tier;

    private Long singleLimitBytes;

    private Long totalLimitBytes;

    private Long usedBytes;

    private Boolean canUpload;

    /** 不能上传时的具体原因；可上传时为 null */
    private String reason;

    /** 今天还能上传几个 */
    private Integer dailyUploadRemaining;

    /** 当前待审数量 */
    private Integer pendingCount;
}
