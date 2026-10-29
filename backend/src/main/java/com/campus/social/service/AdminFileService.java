package com.campus.social.service;

import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileVO;
import org.springframework.http.ResponseEntity;

/**
 * 管理员文件审核（2026-10-27）。
 *
 * <p>文件审核不复用帖子的 review_task：那张表 post_id 非空且带唯一键。
 * 这里直接对 file_asset.status 做条件更新，队列就是 status=0。
 */
public interface AdminFileService {

    FilePageVO list(Integer status, String keyword, int page, int size);

    FileVO detail(long fileId);

    void review(long adminId, long fileId, String action, String reason);

    void takedown(long adminId, long fileId, String reason);

    ResponseEntity<?> download(long fileId);
}
