package com.campus.social.service;

import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件库用户侧能力（2026-10-27）。
 *
 * <p>游客可调的只有 {@link #publicDetail(long, String)}、{@link #download(long, Long, String)}
 * 与 {@link #preview(long, Long, String)}；其余方法都要求登录，
 * 由 AuthInterceptor 的 guest 白名单精确到这三条路径。
 */
public interface FileService {

    FileQuotaVO quota(long userId);

    FilePageVO list(long userId, String tab, String sub, String category,
            String keyword, String sort, int page, int size);

    FileVO detail(long userId, long fileId, String ip);

    FileVO publicDetail(long fileId, String ip);

    long upload(long userId, MultipartFile file, String title, String category, String description);

    ResponseEntity<?> download(long fileId, Long userId, String ip);

    /**
     * 在线预览：只支持 PDF / TXT / MD，其余格式返回 415「请下载查看」。
     *
     * <p>访问口径与下载一致：已过审任何人可看，未过审只有上传者本人。
     * 预览不计下载次数，但同样限流，避免匿名反复拉 3MB 文件。
     */
    ResponseEntity<?> preview(long fileId, Long userId, String ip);

    void delete(long userId, long fileId);

    void like(long userId, long fileId);

    void unlike(long userId, long fileId);

    void favorite(long userId, long fileId);

    void unfavorite(long userId, long fileId);
}
