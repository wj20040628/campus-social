package com.campus.social.service.impl;

import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.FileStorageService;
import com.campus.social.service.support.FileAssetSupport;
import com.campus.social.util.FileCategory;
import com.campus.social.util.FileDocMagic;
import com.campus.social.util.FileLimits;
import com.campus.social.util.OssSignUtil;
import com.campus.social.vo.UserTrustStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.FILE_TOO_LARGE;
import static com.campus.social.common.ResultCode.FILE_TOTAL_QUOTA_EXCEEDED;
import static com.campus.social.common.ResultCode.FILE_UPLOAD_DAILY_LIMIT;
import static com.campus.social.common.ResultCode.FILE_UPLOAD_FORBIDDEN;
import static com.campus.social.common.ResultCode.INTERNAL_ERROR;

/**
 * 文件上传（2026-10-30 从 FileServiceImpl 拆出）。
 *
 * <p>容量把「待审 + 已过审」一起算，且锁 user 行后再 SUM，防并发击穿；
 * 入库失败会删除刚存的对象，避免留下无主 OSS 对象（按量计费）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final FileAssetMapper fileAssetMapper;
    private final UserMapper userMapper;
    private final ReviewTrustService reviewTrustService;
    private final FileStorageService storageService;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;

    @Value("${file.max-bytes:3145728}")
    private long maxFileBytes;

    @Value("${file.daily-upload-limit:5}")
    private int dailyUploadLimit;

    @Value("${file.pending-limit:5}")
    private int pendingLimit;

    public long upload(long userId, MultipartFile file, String title, String category, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "文件为空");
        }
        String cleanTitle = FileAssetSupport.blankToNull(title);
        if (cleanTitle == null || cleanTitle.length() < 2 || cleanTitle.length() > 40) {
            throw new BusinessException(BAD_REQUEST, "文件标题 2–40 字");
        }
        if (!FileCategory.isValid(category)) {
            throw new BusinessException(BAD_REQUEST, "请选择文件分类");
        }
        String cleanDesc = FileAssetSupport.blankToNull(description);
        if (cleanDesc != null && cleanDesc.length() > 100) {
            throw new BusinessException(BAD_REQUEST, "文件简介最多 100 字");
        }

        String originalName = file.getOriginalFilename();
        String ext = FileAssetSupport.extOf(originalName);
        if (!FileDocMagic.isAllowedExt(ext)) {
            throw new BusinessException(BAD_REQUEST,
                    "不支持的文件类型，仅允许：" + String.join("/", FileDocMagic.allowedExts()));
        }
        long hardMax = Math.min(maxFileBytes, FileLimits.MAX_SINGLE_BYTES);
        if (file.getSize() > hardMax) {
            throw new BusinessException(FILE_TOO_LARGE,
                    "单文件不能超过 " + FileLimits.mbText(hardMax));
        }

        // 先做一次不锁的快检，把绝大多数失败挡在读字节和大事务之前
        UserTrustStatusVO st = reviewTrustService.status(userId);
        int tier = FileLimits.tier(st.getCreditScore());
        if (tier == 0) {
            throw new BusinessException(FILE_UPLOAD_FORBIDDEN, "信誉分达到 60 分后可上传文件");
        }
        long singleLimit = FileLimits.singleLimitBytes(tier);
        if (file.getSize() > singleLimit) {
            throw new BusinessException(FILE_TOO_LARGE,
                    "当前 " + tier + " 档单文件最多 " + FileLimits.mbText(singleLimit));
        }
        if (fileAssetMapper.countPendingByUser(userId) >= pendingLimit) {
            throw new BusinessException(FILE_UPLOAD_DAILY_LIMIT,
                    "同时最多 " + pendingLimit + " 个文件待审核，等管理员处理后再传");
        }
        if (fileAssetMapper.countCreatedToday(userId) >= dailyUploadLimit) {
            throw new BusinessException(FILE_UPLOAD_DAILY_LIMIT,
                    "今天最多上传 " + dailyUploadLimit + " 个文件，明天再来");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (MaxUploadSizeExceededException e) {
            throw new BusinessException(FILE_TOO_LARGE, "单文件不能超过 " + FileLimits.mbText(hardMax));
        } catch (IOException e) {
            throw new BusinessException(INTERNAL_ERROR, "读取文件失败");
        }
        if (bytes.length > singleLimit || bytes.length > hardMax) {
            throw new BusinessException(FILE_TOO_LARGE,
                    "当前 " + tier + " 档单文件最多 " + FileLimits.mbText(singleLimit));
        }
        if (!FileDocMagic.matches(ext, bytes)) {
            throw new BusinessException(BAD_REQUEST,
                    "文件内容与后缀 ." + ext + " 不符，请上传真实的文档文件");
        }

        String fileName = FileAssetSupport.safeFileName(originalName, ext);
        String objectKey = storageService.store(userId, ext, bytes, fileName);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Long id = tx.execute(status -> {
                // 锁住 user 行后再 SUM：两个并发上传不能都看到「还差 1MB」而一起通过
                userMapper.lockById(userId);
                UserTrustStatusVO lockedSt = reviewTrustService.status(userId);
                int lockedTier = FileLimits.tier(lockedSt.getCreditScore());
                if (lockedTier == 0) {
                    throw new BusinessException(FILE_UPLOAD_FORBIDDEN,
                            "当前信誉状态不允许上传文件");
                }
                long totalLimit = FileLimits.totalLimitBytes(lockedTier);
                long used = fileAssetMapper.sumActiveBytes(userId);
                if (used + bytes.length > totalLimit) {
                    throw new BusinessException(FILE_TOTAL_QUOTA_EXCEEDED,
                            "当前 " + lockedTier + " 档容量 " + FileLimits.mbText(totalLimit)
                                    + "，已用 " + FileLimits.mbText(used)
                                    + "，删除部分旧文件或提升信誉分后可继续上传");
                }
                if (fileAssetMapper.countPendingByUser(userId) >= pendingLimit) {
                    throw new BusinessException(FILE_UPLOAD_DAILY_LIMIT,
                            "同时最多 " + pendingLimit + " 个文件待审核，等管理员处理后再传");
                }
                if (fileAssetMapper.countCreatedToday(userId) >= dailyUploadLimit) {
                    throw new BusinessException(FILE_UPLOAD_DAILY_LIMIT,
                            "今天最多上传 " + dailyUploadLimit + " 个文件，明天再来");
                }

                FileAsset asset = new FileAsset();
                asset.setUserId(userId);
                asset.setTitle(cleanTitle);
                asset.setDescription(cleanDesc);
                asset.setCategory(category);
                asset.setFileName(fileName);
                asset.setObjectKey(objectKey);
                asset.setExt(ext);
                asset.setMimeType(OssSignUtil.mimeOf(ext));
                asset.setSizeBytes((long) bytes.length);
                asset.setStatus(0);
                asset.setViewCount(0);
                asset.setDownloadCount(0);
                asset.setLikeCount(0);
                asset.setFavoriteCount(0);
                fileAssetMapper.insert(asset);
                return asset.getId();
            });
            if (id == null) {
                throw new BusinessException(INTERNAL_ERROR, "文件入库失败");
            }
            auditService.record(userId, "file_upload", "file", id, cleanTitle, null);
            log.info("file upload: user={}, file={}, size={}, ext={}",
                    userId, id, bytes.length, ext);
            return id;
        } catch (RuntimeException e) {
            // 入库失败不能留下无主对象（OSS 存储按量计费）
            storageService.delete(objectKey);
            throw e;
        }
    }
}
