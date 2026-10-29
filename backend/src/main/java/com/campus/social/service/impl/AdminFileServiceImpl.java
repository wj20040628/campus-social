package com.campus.social.service.impl;

import com.campus.social.common.PageSize;
import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.service.AdminFileService;
import com.campus.social.service.AuditService;
import com.campus.social.service.FileStorageService;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFileServiceImpl implements AdminFileService {

    private final FileAssetMapper fileAssetMapper;
    private final FileStorageService storageService;
    private final FileVoAssembler assembler;
    private final PostNotifySupport notifySupport;
    private final AuditService auditService;

    @Override
    public FilePageVO list(Integer status, String keyword, int page, int size) {
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && kw.isEmpty()) {
            kw = null;
        }
        if (kw != null && kw.length() > 40) {
            throw new BusinessException(BAD_REQUEST, "搜索词最多 40 字");
        }
        int pageSize = PageSize.clamp(size);
        int current = Math.max(1, Math.min(page, 1000));
        int offset = (current - 1) * pageSize;

        long total = fileAssetMapper.countAdminPage(status, kw);
        List<FileAsset> rows = fileAssetMapper.selectAdminPage(status, kw, offset, pageSize);

        FilePageVO vo = new FilePageVO();
        // 管理员视角不显示「我的点赞/收藏/可删除」，统一按游客态组装
        vo.setList(assembler.toVos(rows, 0L, false));
        vo.setTotal(total);
        vo.setPage(current);
        vo.setSize(pageSize);
        vo.setHasMore(offset + rows.size() < total);
        return vo;
    }

    @Override
    public FileVO detail(long fileId) {
        return assembler.toVo(require(fileId), 0L, false);
    }

    @Override
    @Transactional
    public void review(long adminId, long fileId, String action, String reason) {
        FileAsset asset = require(fileId);
        String cleanReason = reason == null ? null : reason.trim();
        if ("approve".equals(action)) {
            int affected = fileAssetMapper.casReview(fileId, 1, adminId, null);
            if (affected == 0) {
                throw new BusinessException(UNPROCESSABLE, "该文件已被处理，请刷新队列");
            }
            auditService.record(adminId, "file_approve", "file", fileId, asset.getTitle(), null);
            notifySupport.pushNotify(asset.getUserId(), 13, fileId, adminId, null);
            log.info("file approve: admin={}, file={}", adminId, fileId);
            return;
        }
        if (!"reject".equals(action)) {
            throw new BusinessException(UNPROCESSABLE, "审核动作只能是 approve / reject");
        }
        if (cleanReason == null || cleanReason.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "驳回必须填写理由");
        }
        if (cleanReason.length() > 200) {
            throw new BusinessException(BAD_REQUEST, "理由最多 200 字");
        }
        int affected = fileAssetMapper.casReview(fileId, 2, adminId, cleanReason);
        if (affected == 0) {
            throw new BusinessException(UNPROCESSABLE, "该文件已被处理，请刷新队列");
        }
        auditService.record(adminId, "file_reject", "file", fileId,
                cleanReason, null);
        notifySupport.pushNotify(asset.getUserId(), 14, fileId, adminId, null);
        log.info("file reject: admin={}, file={}, reason={}", adminId, fileId, cleanReason);
    }

    @Override
    @Transactional
    public void takedown(long adminId, long fileId, String reason) {
        FileAsset asset = require(fileId);
        String cleanReason = reason == null ? null : reason.trim();
        if (cleanReason == null || cleanReason.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "下架必须填写理由");
        }
        if (cleanReason.length() > 200) {
            throw new BusinessException(BAD_REQUEST, "理由最多 200 字");
        }
        int affected = fileAssetMapper.takedown(fileId, adminId, cleanReason);
        if (affected == 0) {
            throw new BusinessException(UNPROCESSABLE, "只有已通过审核的文件可以下架");
        }
        auditService.record(adminId, "file_takedown", "file", fileId,
                cleanReason, null);
        notifySupport.pushNotify(asset.getUserId(), 15, fileId, adminId, null);
        log.info("file takedown: admin={}, file={}, reason={}", adminId, fileId, cleanReason);
    }

    @Override
    public ResponseEntity<?> download(long fileId) {
        FileAsset asset = require(fileId);
        if (asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            throw new BusinessException(FILE_NOT_AVAILABLE, "文件内容已清理");
        }
        return storageService.downloadBytesResponse(asset.getObjectKey(), asset.getFileName());
    }

    private FileAsset require(long fileId) {
        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        return asset;
    }
}
