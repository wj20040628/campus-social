package com.campus.social.task;

import com.campus.social.entity.FileAsset;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件对象清理（2026-10-27）。
 *
 * <p>驳回/下架的记录要留作审核证据，但对象不长期占存储：
 * 7 天后删对象、保留行与理由。用户主动删除走即时删除，不经过这里。
 *
 * <p>只做「DB 记录 → 对象」方向的清理；不扫 OSS 列目录，
 * 因为当前 ECS RAM 角色没有 ListObjects 权限，也不需要为低频兜底放大权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilePurgeJob {

    private static final int RETENTION_DAYS = 7;

    private final FileAssetMapper fileAssetMapper;
    private final FileStorageService storageService;

    @Scheduled(cron = "0 40 3 * * ?")
    public void purgeRejectedAndTakenDown() {
        LocalDateTime before = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<FileAsset> rows = fileAssetMapper.selectObjectsToPurge(before);
        int purged = 0;
        for (FileAsset row : rows) {
            if (storageService.delete(row.getObjectKey())) {
                fileAssetMapper.clearObjectKey(row.getId());
                purged++;
            }
        }
        if (purged > 0) {
            log.info("file purge: cleaned {} rejected/taken-down objects", purged);
        }
    }
}
