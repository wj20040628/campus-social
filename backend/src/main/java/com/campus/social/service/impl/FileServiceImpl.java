package com.campus.social.service.impl;

import com.campus.social.service.FileService;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件库服务门面（2026-10-30 拆分）。
 *
 * <p>读取 → {@link FileQueryService}，上传 → {@link FileUploadService}，
 * 下载/预览 → {@link FileAccessService}，删除/点赞/收藏 → {@link FileInteractionService}。
 * 事务边界留在门面，写方法仍是一个事务。
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileQueryService query;
    private final FileUploadService uploadService;
    private final FileAccessService access;
    private final FileInteractionService interactions;

    @Override
    public FileQuotaVO quota(long userId) {
        return query.quota(userId);
    }

    @Override
    public FilePageVO list(long userId, String tab, String sub, String category,
            String keyword, String sort, int page, int size) {
        return query.list(userId, tab, sub, category, keyword, sort, page, size);
    }

    @Override
    public FileVO detail(long userId, long fileId, String ip) {
        return query.detail(userId, fileId, ip);
    }

    @Override
    public FileVO publicDetail(long fileId, String ip) {
        return query.publicDetail(fileId, ip);
    }

    @Override
    public long upload(long userId, MultipartFile file, String title, String category, String description) {
        return uploadService.upload(userId, file, title, category, description);
    }

    @Override
    public ResponseEntity<?> download(long fileId, Long userId, String ip) {
        return access.download(fileId, userId, ip);
    }

    @Override
    public ResponseEntity<?> preview(long fileId, Long userId, String ip) {
        return access.preview(fileId, userId, ip);
    }

    @Override
    @Transactional
    public void delete(long userId, long fileId) {
        interactions.delete(userId, fileId);
    }

    @Override
    @Transactional
    public void like(long userId, long fileId) {
        interactions.like(userId, fileId);
    }

    @Override
    @Transactional
    public void unlike(long userId, long fileId) {
        interactions.unlike(userId, fileId);
    }

    @Override
    @Transactional
    public void favorite(long userId, long fileId) {
        interactions.favorite(userId, fileId);
    }

    @Override
    @Transactional
    public void unfavorite(long userId, long fileId) {
        interactions.unfavorite(userId, fileId);
    }
}
