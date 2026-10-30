package com.campus.social.service.impl;

import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.service.FileStorageService;
import com.campus.social.service.support.FileAssetSupport;
import com.campus.social.util.FilePreview;
import com.campus.social.util.RedisLua;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static com.campus.social.common.ResultCode.FILE_NOT_AVAILABLE;
import static com.campus.social.common.ResultCode.TOO_MANY_REQUESTS;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 文件下载与预览（2026-10-30 从 FileServiceImpl 拆出）。
 *
 * <p>下载只经本服务的签名端点；下载/预览分别限流（预览不占下载额度），
 * 下载量按人/天去重。上传者预览自己的待审/驳回文件不计入公开下载量。
 */
@Service
@RequiredArgsConstructor
public class FileAccessService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private final FileAssetSupport assets;
    private final FileAssetMapper fileAssetMapper;
    private final FileStorageService storageService;
    private final StringRedisTemplate redis;
    private final RedisLua redisLua;

    @Value("${file.download.guest-hourly:120}")
    private int guestHourlyLimit;

    @Value("${file.download.guest-daily:1000}")
    private int guestDailyLimit;

    @Value("${file.download.user-daily:2000}")
    private int userDailyLimit;

    public ResponseEntity<?> download(long fileId, Long userId, String ip) {
        FileAsset asset = assets.requireAsset(fileId);
        boolean owner = userId != null && userId > 0 && asset.getUserId().equals(userId);
        if (!Integer.valueOf(1).equals(asset.getStatus()) && !owner) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        long now = System.currentTimeMillis();
        boolean logged = userId != null && userId > 0;
        String who = logged ? "u" + userId : "ip" + FileAssetSupport.normIp(ip);
        if (logged) {
            if (!redisLua.tryAcquire("file:dl:user:" + userId, now - DAY_MS, userDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的下载次数用完了，明天再来");
            }
        } else {
            if (!redisLua.tryAcquire("file:dl:ip:h:" + FileAssetSupport.normIp(ip), now - HOUR_MS, guestHourlyLimit,
                    now, now + "-" + UUID.randomUUID(), HOUR_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "下载太频繁，请稍后再试");
            }
            if (!redisLua.tryAcquire("file:dl:ip:d:" + FileAssetSupport.normIp(ip), now - DAY_MS, guestDailyLimit,
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

    public ResponseEntity<?> preview(long fileId, Long userId, String ip) {
        FileAsset asset = assets.requireAsset(fileId);
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
            if (!redisLua.tryAcquire("file:pv:ip:h:" + FileAssetSupport.normIp(ip), now - HOUR_MS, guestHourlyLimit,
                    now, now + "-" + UUID.randomUUID(), HOUR_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "预览太频繁，请稍后再试");
            }
            if (!redisLua.tryAcquire("file:pv:ip:d:" + FileAssetSupport.normIp(ip), now - DAY_MS, guestDailyLimit,
                    now, now + "-" + UUID.randomUUID(), DAY_MS)) {
                throw new BusinessException(TOO_MANY_REQUESTS, "今天的预览次数用完了，明天再来");
            }
        }
        return storageService.previewResponse(asset.getObjectKey(), asset.getFileName(), contentType);
    }
}
