package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.service.AdminFileService;
import com.campus.social.service.FileService;
import com.campus.social.service.FileStorageService;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件库回归（2026-10-27）。
 *
 * <p>覆盖设计里最容易写错的几件事：待审不可见、过审后才 +1 信誉分、
 * 待审/已过审都占容量、驳回释放、点赞收藏幂等、删除释放。
 * 自播种自清理；上传对象写本地 upload.dir/files，经过服务删除。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class FileLibraryTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private FileService fileService;
    @Autowired
    private AdminFileService adminFileService;
    @Autowired
    private FileAssetMapper fileAssetMapper;
    @Autowired
    private FileStorageService storageService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private Long userId;
    private Long unverifiedId;
    private Long lowCreditId;

    @BeforeEach
    void seed() {
        userId = insertUser("file-user-" + SUFFIX, 1);
        unverifiedId = insertUser("file-unver-" + SUFFIX, 0);
        lowCreditId = insertUser("file-low-" + SUFFIX, 0);
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : List.of(userId, unverifiedId, lowCreditId)) {
            if (id == null) continue;
            List<String> objectKeys = jdbc.queryForList(
                    "SELECT object_key FROM file_asset WHERE user_id = ? AND object_key <> ''",
                    String.class, id);
            for (String key : objectKeys) {
                storageService.delete(key);
            }
            jdbc.update("DELETE FROM file_asset WHERE user_id = ?", id);
            jdbc.update("DELETE FROM file_favorite WHERE user_id = ?", id);
            jdbc.update("DELETE FROM `like` WHERE user_id = ? AND target_type = 3", id);
            jdbc.update("DELETE FROM notify WHERE user_id = ? AND type IN (13,14,15)", id);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ? AND action IN "
                    + "('file_upload','file_delete','file_approve','file_reject','file_takedown',"
                    + "'report_upheld')", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
        Set<String> keys = redis.keys("file:view:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        keys = redis.keys("file:dl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        keys = redis.keys("file:dlcount:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        keys = redis.keys("file:pv:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void previewIsInlineForPdfAndOwnerOnlyBeforeApproval() {
        long fileId = upload("预览测试提纲", "guide");

        // 上传者能预览自己的待审文件；返回 inline PDF 而不是 attachment
        ResponseEntity<?> owner = fileService.preview(fileId, userId, "127.0.0.1");
        assertEquals(200, owner.getStatusCode().value());
        assertEquals("application/pdf", String.valueOf(owner.getHeaders().getContentType()));
        assertTrue(String.valueOf(owner.getHeaders().getFirst("Content-Disposition"))
                .startsWith("inline"), "预览必须是 inline，否则浏览器会下载");

        // 他人（含游客）不能预览待审文件
        BusinessException guest = assertThrows(BusinessException.class,
                () -> fileService.preview(fileId, null, "10.9.9.9"));
        assertEquals(1072, guest.getCode());

        adminFileService.review(1L, fileId, "approve", null);
        ResponseEntity<?> pub = fileService.preview(fileId, null, "10.9.9.8");
        assertEquals(200, pub.getStatusCode().value());

        // 不支持的格式：422 提示下载，不读取对象
        String docxKey = "files/test/" + SUFFIX + ".docx";
        jdbc.update("INSERT INTO file_asset (user_id, title, category, file_name, object_key, ext, "
                        + "mime_type, size_bytes, status) VALUES (?,?,?,?,?,?,?,?,1)",
                userId, "不支持预览", "other", "x.docx", docxKey,
                "docx", "application/octet-stream", 100L);
        Long docxId = jdbc.queryForObject("SELECT id FROM file_asset WHERE object_key = ?", Long.class, docxKey);
        BusinessException unsupported = assertThrows(BusinessException.class,
                () -> fileService.preview(docxId, userId, "127.0.0.1"));
        assertEquals(422, unsupported.getCode());
    }

    @Test
    void uploadIsPendingThenApproveMakesItPublicAndAddsCredit() {
        long fileId = upload("高数期末复习提纲", "guide");
        FileVO mine = fileService.detail(userId, fileId, "127.0.0.1");
        assertEquals(0, mine.getStatus(), "上传后是待审，不是公开");
        assertEquals(0, fileService.list(userId, "all", null, null, null, "latest", 1, 20)
                .getList().stream().filter(f -> f.getId().equals(fileId)).count(),
                "待审文件不能出现在全部列表");

        BusinessException notPublic = assertThrows(BusinessException.class,
                () -> fileService.publicDetail(fileId, "127.0.0.1"));
        assertEquals(1072, notPublic.getCode());

        // 上传者自己可以下载待审文件核对内容
        ResponseEntity<?> ownerDownload = fileService.download(fileId, userId, "127.0.0.1");
        assertEquals(200, ownerDownload.getStatusCode().value());

        adminFileService.review(1L, fileId, "approve", null);

        FileVO approved = fileService.detail(userId, fileId, "127.0.0.1");
        assertEquals(1, approved.getStatus());
        assertTrue(fileService.publicDetail(fileId, "127.0.0.1").getTitle().contains("高数"));
        assertTrue(fileService.list(userId, "all", null, null, null, "latest", 1, 20)
                .getList().stream().anyMatch(f -> f.getId().equals(fileId)));

        FileQuotaVO quota = fileService.quota(userId);
        assertEquals(61, quota.getScore(), "过审文件给基准 60 加 1 分");
        assertEquals(1, quota.getTier());
        assertTrue(quota.getUsedBytes() >= PDF.length);

        assertFalse(approved.getMyLike());
        fileService.like(userId, fileId);
        fileService.like(userId, fileId); // 幂等
        assertEquals(1, fileService.detail(userId, fileId, "127.0.0.1").getLikeCount());

        fileService.favorite(userId, fileId);
        fileService.favorite(userId, fileId); // 幂等
        assertEquals(1, fileService.detail(userId, fileId, "127.0.0.1").getFavoriteCount());

        fileService.unlike(userId, fileId);
        fileService.unfavorite(userId, fileId);
        FileVO afterCancel = fileService.detail(userId, fileId, "127.0.0.1");
        assertEquals(0, afterCancel.getLikeCount());
        assertEquals(0, afterCancel.getFavoriteCount());
    }

    @Test
    void rejectReleasesQuotaAndNotifiesUploader() {
        long fileId = upload("写坏的模板", "template");
        adminFileService.review(1L, fileId, "reject", "文件打不开");

        FileVO rejected = fileService.detail(userId, fileId, "127.0.0.1");
        assertEquals(2, rejected.getStatus());
        assertEquals("文件打不开", rejected.getRejectReason());
        assertEquals(0L, fileService.quota(userId).getUsedBytes(), "驳回不占容量");
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify WHERE user_id = ? AND type = 14 AND target_id = ?",
                Integer.class, userId, fileId);
        assertEquals(1, n);
    }

    @Test
    void totalQuotaCountsPendingAndApprovedAndBlocksUpload() {
        // 直接种 3 条已过审记录，把 1 档的 5MB 容量占满到 6MB
        for (int i = 0; i < 3; i++) {
            FileAsset a = new FileAsset();
            a.setUserId(userId);
            a.setTitle("占位" + i);
            a.setCategory("other");
            a.setFileName("placeholder-" + i + ".pdf");
            a.setObjectKey("files/" + userId + "/20990101/placeholder-" + i + ".pdf");
            a.setExt("pdf");
            a.setMimeType("application/octet-stream");
            a.setSizeBytes(2L * 1024 * 1024);
            a.setStatus(1);
            a.setViewCount(0);
            a.setDownloadCount(0);
            a.setLikeCount(0);
            a.setFavoriteCount(0);
            fileAssetMapper.insert(a);
        }

        FileQuotaVO quota = fileService.quota(userId);
        assertEquals(1, quota.getTier());
        assertEquals(6L * 1024 * 1024, quota.getUsedBytes());
        assertFalse(quota.getCanUpload(), "超额必须禁止上传");

        BusinessException e = assertThrows(BusinessException.class,
                () -> upload("再传一个", "other"));
        assertEquals(1070, e.getCode());

        // 删掉其中一条 → 容量回到 4MB，恢复上传
        Long first = jdbc.queryForObject("SELECT MIN(id) FROM file_asset WHERE user_id = ?", Long.class, userId);
        fileService.delete(userId, first);
        assertEquals(4L * 1024 * 1024, fileService.quota(userId).getUsedBytes());
        assertTrue(fileService.quota(userId).getCanUpload());
    }

    /** 校园信息不再参与文件权限：未填写但信誉分达线可以上传 */
    @Test
    void campusInfoNoLongerRequiredForUpload() {
        assertDoesNotThrow(() -> fileService.upload(unverifiedId,
                new MockMultipartFile("file", "a.pdf", "application/pdf", PDF),
                "未填校园信息", "other", null));
    }

    /** 信誉分低于 60 → 1068；两条成立举报把基准 60 扣到 20 */
    @Test
    void lowCreditUserCannotUpload() {
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                    + "VALUES (1, 'report_upheld', 'user', ?, ?)", lowCreditId, "LC" + i + SUFFIX);
        }

        BusinessException e = assertThrows(BusinessException.class,
                () -> fileService.upload(lowCreditId,
                        new MockMultipartFile("file", "a.pdf", "application/pdf", PDF),
                        "低信誉", "other", null));
        assertEquals(ResultCode.FILE_UPLOAD_FORBIDDEN.getCode(), e.getCode());
    }

    private long upload(String title, String category) {
        return fileService.upload(userId,
                new MockMultipartFile("file", title + ".pdf", "application/pdf", PDF),
                title, category, "测试文件");
    }
}
