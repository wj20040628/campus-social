package com.campus.social.service.impl;

import com.campus.social.common.PageSize;
import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.service.support.FileAssetSupport;
import com.campus.social.util.FileCategory;
import com.campus.social.util.FileLimits;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import com.campus.social.vo.UserTrustStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.FILE_NOT_AVAILABLE;

/**
 * 文件库读取（2026-10-30 从 FileServiceImpl 拆出）：配额、列表、详情。
 * 待审/驳回/下架对他人不可见，浏览量按人/天去重。
 */
@Service
@RequiredArgsConstructor
public class FileQueryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FileAssetSupport assets;
    private final FileAssetMapper fileAssetMapper;
    private final ReviewTrustService reviewTrustService;
    private final FileVoAssembler assembler;
    private final StringRedisTemplate redis;

    @Value("${file.daily-upload-limit:5}")
    private int dailyUploadLimit;

    @Value("${file.pending-limit:5}")
    private int pendingLimit;

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

    public FilePageVO list(long userId, String tab, String sub, String category,
            String keyword, String sort, int page, int size) {
        boolean mine = "mine".equals(tab);
        boolean favorites = mine && "favorites".equals(sub);
        String categoryFilter = FileAssetSupport.blankToNull(category);
        if (categoryFilter != null && !FileCategory.isValid(categoryFilter)) {
            throw new BusinessException(BAD_REQUEST, "文件分类不存在");
        }
        String kw = FileAssetSupport.blankToNull(keyword);
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

    public FileVO detail(long userId, long fileId, String ip) {
        FileAsset asset = assets.requireAsset(fileId);
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

    public FileVO publicDetail(long fileId, String ip) {
        FileAsset asset = assets.requireAsset(fileId);
        if (!Integer.valueOf(1).equals(asset.getStatus())) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        if (countView(asset.getId(), "ip" + FileAssetSupport.normIp(ip))) {
            asset.setViewCount((asset.getViewCount() == null ? 0 : asset.getViewCount()) + 1);
        }
        return assembler.toVo(asset, 0L, true);
    }

    /** 同一人/同一 IP 每天只计一次浏览 */
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
}
