package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.FileAsset;
import com.campus.social.entity.FileFavorite;
import com.campus.social.entity.Like;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.FileFavoriteMapper;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.FileStorageService;
import com.campus.social.service.support.FileAssetSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.FORBIDDEN;

/**
 * 文件互动与删除（2026-10-30 从 FileServiceImpl 拆出）：
 * 删除（仅上传者）、点赞、收藏。点赞/收藏靠唯一键兜底并发重复，操作幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileInteractionService {

    private static final int LIKE_TARGET_FILE = FileVoAssembler.LIKE_TARGET_FILE;

    private final FileAssetSupport assets;
    private final FileAssetMapper fileAssetMapper;
    private final FileFavoriteMapper fileFavoriteMapper;
    private final LikeMapper likeMapper;
    private final FileStorageService storageService;
    private final AuditService auditService;

    public void delete(long userId, long fileId) {
        FileAsset asset = assets.requireAsset(fileId);
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

    public void like(long userId, long fileId) {
        assets.requireApproved(fileId);
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

    public void unlike(long userId, long fileId) {
        int deleted = likeMapper.hardDelete(userId, LIKE_TARGET_FILE, fileId);
        if (deleted > 0) {
            fileAssetMapper.adjustLikeCount(fileId, -1);
        }
    }

    public void favorite(long userId, long fileId) {
        assets.requireApproved(fileId);
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

    public void unfavorite(long userId, long fileId) {
        int deleted = fileFavoriteMapper.hardDelete(userId, fileId);
        if (deleted > 0) {
            fileAssetMapper.adjustFavoriteCount(fileId, -1);
        }
    }
}
