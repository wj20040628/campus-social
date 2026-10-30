package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.PageSize;
import com.campus.social.entity.FileAsset;
import com.campus.social.entity.FileFavorite;
import com.campus.social.entity.Like;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.FileFavoriteMapper;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.FileService;
import com.campus.social.service.FileStorageService;
import com.campus.social.util.FileCategory;
import com.campus.social.util.FileDocMagic;
import com.campus.social.util.FileLimits;
import com.campus.social.util.FilePreview;
import com.campus.social.util.OssSignUtil;
import com.campus.social.util.RedisLua;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import com.campus.social.vo.UserTrustStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.campus.social.common.ResultCode.*;

/**
 * 文件库用户侧实现（2026-10-27）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>文件对象存 {@code files/} 私有前缀，下载只经本服务的签名端点；</li>
 *   <li>容量把「待审 + 已过审」一起算，且锁 user 行后再 SUM，防并发击穿；</li>
 *   <li>好评/差评、审核结果都靠状态条件更新，重复操作幂等或明确报错。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;
    private static final int LIKE_TARGET_FILE = FileVoAssembler.LIKE_TARGET_FILE;

    private final FileAssetMapper fileAssetMapper;
    private final FileFavoriteMapper fileFavoriteMapper;
    private final LikeMapper likeMapper;
    private final UserMapper userMapper;
    private final ReviewTrustService reviewTrustService;
    private final FileStorageService storageService;
    private final FileVoAssembler assembler;
    private final StringRedisTemplate redis;
    private final RedisLua redisLua;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;

    @Value("${file.max-bytes:3145728}")
    private long maxFileBytes;

    @Value("${file.daily-upload-limit:5}")
    private int dailyUploadLimit;

    @Value("${file.pending-limit:5}")
    private int pendingLimit;

    @Value("${file.download.guest-hourly:120}")
    private int guestHourlyLimit;

    @Value("${file.download.guest-daily:1000}")
    private int guestDailyLimit;

    @Value("${file.download.user-daily:2000}")
    private int userDailyLimit;

    @Override
    public FileQuotaVO quota(long userId) {
        UserTrustStatusVO st = reviewTrustService.status(userId);
        int tier = FileLimits.tier(st.getCreditScore());
        long used = fileAssetMapper.sumActiveBytes(userId);
        int pending = fileAssetMapper.countPendingByUser(userId);
        int today = fileAssetMapper.countCreatedToday(userId);

        FileQuotaVO vo = new FileQuotaVO();
        vo.setScore(st.getCreditScore());
        vo.setVerified(st.getVerified());
        vo.setTier(tier);
        vo.setSingleLimitBytes(FileLimits.singleLimitBytes(tier));
        vo.setTotalLimitBytes(FileLimits.totalLimitBytes(tier));
        vo.setUsedBytes(used);
        vo.setPendingCount(pending);
        vo.setDailyUploadRemaining(Math.max(0, dailyUploadLimit - today));

        String reason = null;
        if (tier == 0) {
            reason = "信誉分达到 60 分后可上传文件";
        } else if (used >= FileLimits.totalLimitBytes(tier)) {
            reason = "文件容量已满，删除部分旧文件或提升信誉分后可继续上传";
        } else if (pending >= pendingLimit) {
            reason = "同时最多 " + pendingLimit + " 个文件待审核，等管理员处理后再传";
        } else if (today >= dailyUploadLimit) {
            reason = "今天最多上传 " + dailyUploadLimit + " 个文件，明天再来";
        }
        vo.setCanUpload(reason == null);
        vo.setReason(reason);
        return vo;
    }

    @Override
    public FilePageVO list(long userId, String tab, String sub, String category,
            String keyword, String sort, int page, int size) {
        boolean mine = "mine".equals(tab);
        boolean favorites = mine && "favorites".equals(sub);
        String categoryFilter = blankToNull(category);
        if (categoryFilter != null && !FileCategory.isValid(categoryFilter)) {
            throw new BusinessException(BAD_REQUEST, "文件分类不存在");
        }
        String kw = blankToNull(keyword);
        if (kw != null && kw.length() > 40) {
            throw new BusinessException(BAD_REQUEST, "搜索词最多 40 字");
        }
        boolean hot = "hot".equals(sort);
        int pageSize = PageSize.clamp(size);
        int current = Math.max(1, Math.min(page, 1000));
        int offset = (current - 1) * pageSize;

        long total = fileAssetMapper.countPage(userId, mine, favorites, categoryFilter, kw);
        List<FileAsset> rows = fileAssetMapper.selectPage(
                userId, mine, favorites, categoryFilter, kw, hot, offset, pageSize);

        FilePageVO vo = new FilePageVO();
        vo.setList(assembler.toVos(rows, userId, false));
        vo.setTotal(total);
        vo.setPage(current);
        vo.setSize(pageSize);
        vo.setHasMore(offset + rows.size() < total);
        return vo;
    }

    @Override
    public FileVO detail(long userId, long fileId, String ip) {
        FileAsset asset = requireAsset(fileId);
        if (!Integer.valueOf(1).equals(asset.getStatus()) && !asset.getUserId().equals(userId)) {
            // 待审/驳回/下架对他人不可见，也不区分「存在但不可见」
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (Integer.valueOf(1).equals(asset.getStatus())) {
            if (countView(asset.getId(), "u" + userId)) {
                // 数据库已 +1，但 VO 用的还是旧快照；不回写的话首次打开永远显示少 1
                asset.setViewCount((asset.getViewCount() == null ? 0 : asset.getViewCount()) + 1);
            }
        }
        return assembler.toVo(asset, userId, false);
    }

    @Override
    public FileVO publicDetail(long fileId, String ip) {
        FileAsset asset = requireAsset(fileId);
        if (!Integer.valueOf(1).equals(asset.getStatus())) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (countView(asset.getId(), "ip" + normIp(ip))) {
            asset.setViewCount((asset.getViewCount() == null ? 0 : asset.getViewCount()) + 1);
        }
        return assembler.toVo(asset, 0L, true);
    }

    @Override
    public long upload(long userId, MultipartFile file, String title, String category, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "文件为空");
        }
        String cleanTitle = blankToNull(title);
        if (cleanTitle == null || cleanTitle.length() < 2 || cleanTitle.length() > 40) {
            throw new BusinessException(BAD_REQUEST, "文件标题 2–40 字");
        }
        if (!FileCategory.isValid(category)) {
            throw new BusinessException(BAD_REQUEST, "请选择文件分类");
        }
        String cleanDesc = blankToNull(description);
        if (cleanDesc != null && cleanDesc.length() > 100) {
            throw new BusinessException(BAD_REQUEST, "文件简介最多 100 字");
        }

        String originalName = file.getOriginalFilename();
        String ext = extOf(originalName);
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

        String fileName = safeFileName(originalName, ext);
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

    @Override
    public ResponseEntity<?> download(long fileId, Long userId, String ip) {
        FileAsset asset = requireAsset(fileId);
        boolean owner = userId != null && userId > 0 && asset.getUserId().equals(userId);
        if (!Integer.valueOf(1).equals(asset.getStatus()) && !owner) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        long now = System.currentTimeMillis();
        boolean logged = userId != null && userId > 0;
        String who = logged ? "u" + userId : "ip" + normIp(ip);
        if (logged) {
            if (!redisLua.tryAcquire("file:dl:user:" + userId, now - DAY_MS, userDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的下载次数用完了，明天再来");
            }
        } else {
            if (!redisLua.tryAcquire("file:dl:ip:h:" + normIp(ip), now - HOUR_MS, guestHourlyLimit,
                    now, now + "-" + UUID.randomUUID(), HOUR_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "下载太频繁，请稍后再试");
            }
            if (!redisLua.tryAcquire("file:dl:ip:d:" + normIp(ip), now - DAY_MS, guestDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的下载次数用完了，明天再来");
            }
        }
        // 下载量按人/天去重：同一个人反复点不把数字刷成假的。
        // 上传者预览自己的待审/驳回文件不计入公开下载量。
        if (Integer.valueOf(1).equals(asset.getStatus())) {
            String day = LocalDate.now().format(DAY);
            String countKey = "file:dlcount:" + fileId + ":" + who + ":" + day;
            Boolean first = redis.opsForValue().setIfAbsent(countKey, "1", Duration.ofDays(2));
            if (Boolean.TRUE.equals(first)) {
                fileAssetMapper.incrementDownload(fileId);
            }
        }
        return storageService.downloadResponse(asset.getObjectKey(), asset.getFileName(), asset.getExt());
    }

    @Override
    public ResponseEntity<?> preview(long fileId, Long userId, String ip) {
        FileAsset asset = requireAsset(fileId);
        boolean owner = userId != null && userId > 0 && asset.getUserId().equals(userId);
        if (!Integer.valueOf(1).equals(asset.getStatus()) && !owner) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        String contentType = FilePreview.contentType(asset.getExt());
        if (contentType == null) {
            throw new BusinessException(UNPROCESSABLE, "该格式暂不支持在线预览，请下载后用本地软件打开");
        }
        /*
         * 预览同样消耗带宽，沿用下载的限额但不复用 file:dl 键：
         * 预览不该占用「下载次数」，否则看几份资料就把当天下载额度用完了。
         */
        long now = System.currentTimeMillis();
        boolean logged = userId != null && userId > 0;
        if (logged) {
            if (!redisLua.tryAcquire("file:pv:user:" + userId, now - DAY_MS, userDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的预览次数用完了，明天再来");
            }
        } else {
            if (!redisLua.tryAcquire("file:pv:ip:h:" + normIp(ip), now - HOUR_MS, guestHourlyLimit,
                    now, now + "-" + UUID.randomUUID(), HOUR_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "预览太频繁，请稍后再试");
            }
            if (!redisLua.tryAcquire("file:pv:ip:d:" + normIp(ip), now - DAY_MS, guestDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的预览次数用完了，明天再来");
            }
        }
        return storageService.previewResponse(asset.getObjectKey(), asset.getFileName(), contentType);
    }

    @Override
    @Transactional
    public void delete(long userId, long fileId) {
        FileAsset asset = requireAsset(fileId);
        if (!asset.getUserId().equals(userId)) {
            throw new BusinessException(FORBIDDEN, "只能删除自己上传的文件");
        }
        fileFavoriteMapper.hardDeleteByFile(fileId);
        likeMapper.hardDeleteByTarget(LIKE_TARGET_FILE, fileId);
        fileAssetMapper.deleteById(fileId);
        // 对象删除放在 DB 之后：DB 成功而对象删失败最多留一个孤儿，
        // 反过来会出现「记录还在、文件打不开」的坏状态
        storageService.delete(asset.getObjectKey());
        auditService.record(userId, "file_delete", "file", fileId, asset.getTitle(), null);
        log.info("file delete: user={}, file={}", userId, fileId);
    }

    @Override
    @Transactional
    public void like(long userId, long fileId) {
        requireApproved(fileId);
        Long count = likeMapper.selectCount(new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, LIKE_TARGET_FILE)
                .eq(Like::getTargetId, fileId));
        if (count != null && count > 0) {
            return;
        }
        Like like = new Like();
        like.setUserId(userId);
        like.setTargetType(LIKE_TARGET_FILE);
        like.setTargetId(fileId);
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            // 并发双击：唯一键兜底，当作已点赞
            return;
        }
        fileAssetMapper.adjustLikeCount(fileId, 1);
    }

    @Override
    @Transactional
    public void unlike(long userId, long fileId) {
        int deleted = likeMapper.hardDelete(userId, LIKE_TARGET_FILE, fileId);
        if (deleted > 0) {
            fileAssetMapper.adjustLikeCount(fileId, -1);
        }
    }

    @Override
    @Transactional
    public void favorite(long userId, long fileId) {
        requireApproved(fileId);
        Long count = fileFavoriteMapper.selectCount(new LambdaQueryWrapper<FileFavorite>()
                .eq(FileFavorite::getUserId, userId)
                .eq(FileFavorite::getFileId, fileId));
        if (count != null && count > 0) {
            return;
        }
        FileFavorite favorite = new FileFavorite();
        favorite.setUserId(userId);
        favorite.setFileId(fileId);
        try {
            fileFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            return;
        }
        fileAssetMapper.adjustFavoriteCount(fileId, 1);
    }

    @Override
    @Transactional
    public void unfavorite(long userId, long fileId) {
        int deleted = fileFavoriteMapper.hardDelete(userId, fileId);
        if (deleted > 0) {
            fileAssetMapper.adjustFavoriteCount(fileId, -1);
        }
    }

    private FileAsset requireAsset(long fileId) {
        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        return asset;
    }

    private void requireApproved(long fileId) {
        FileAsset asset = requireAsset(fileId);
        if (!Integer.valueOf(1).equals(asset.getStatus())) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
    }

    private boolean countView(long fileId, String viewerKey) {
        String day = LocalDate.now().format(DAY);
        String key = "file:view:" + fileId + ":" + viewerKey + ":" + day;
        Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(2));
        if (Boolean.TRUE.equals(first)) {
            fileAssetMapper.incrementView(fileId);
            return true;
        }
        return false;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 原文件名只用于展示/下载响应头：去掉路径、控制字符，限长 120 */
    private static String safeFileName(String original, String ext) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            return "file." + ext;
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String suffix = dot > 0 ? name.substring(dot) : "";
            int baseLen = Math.max(1, 120 - suffix.length());
            name = name.substring(0, Math.min(baseLen, name.length())) + suffix;
        }
        return name;
    }

    private static String normIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        String t = ip.trim();
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
