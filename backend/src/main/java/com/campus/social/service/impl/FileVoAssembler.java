package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.FileAsset;
import com.campus.social.entity.FileFavorite;
import com.campus.social.entity.Like;
import com.campus.social.entity.User;
import com.campus.social.mapper.FileFavoriteMapper;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.vo.FileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FileAsset → FileVO（2026-10-27）。
 *
 * <p>列表页一次批量取上传者 / 点赞 / 收藏，避免每行三次查询。
 * 用户态字段（myLike/myFavorite/canDelete）只在登录视角计算；
 * 公开分享页统一置 false。
 */
@Service
@RequiredArgsConstructor
public class FileVoAssembler {

    public static final int LIKE_TARGET_FILE = 3;

    private final UserMapper userMapper;
    private final LikeMapper likeMapper;
    private final FileFavoriteMapper favoriteMapper;

    public FileVO toVo(FileAsset asset, long viewerId, boolean publicView) {
        return toVos(List.of(asset), viewerId, publicView).get(0);
    }

    public List<FileVO> toVos(List<FileAsset> assets, long viewerId, boolean publicView) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        Set<Long> uploaderIds = assets.stream()
                .map(FileAsset::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> users = userMapper.selectByIds(uploaderIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<Long> fileIds = assets.stream().map(FileAsset::getId).collect(Collectors.toSet());
        Set<Long> liked = new HashSet<>();
        Set<Long> favorited = new HashSet<>();
        if (!publicView && viewerId > 0) {
            liked = likeMapper.selectList(new LambdaQueryWrapper<Like>()
                            .eq(Like::getUserId, viewerId)
                            .eq(Like::getTargetType, LIKE_TARGET_FILE)
                            .in(Like::getTargetId, fileIds))
                    .stream().map(Like::getTargetId).collect(Collectors.toSet());
            favorited = favoriteMapper.selectList(new LambdaQueryWrapper<FileFavorite>()
                            .eq(FileFavorite::getUserId, viewerId)
                            .in(FileFavorite::getFileId, fileIds))
                    .stream().map(FileFavorite::getFileId).collect(Collectors.toSet());
        }

        Set<Long> finalLiked = liked;
        Set<Long> finalFavorited = favorited;
        return assets.stream().map(a -> {
            User u = users.get(a.getUserId());
            FileVO vo = new FileVO();
            vo.setId(a.getId());
            vo.setTitle(a.getTitle());
            vo.setDescription(a.getDescription());
            vo.setCategory(a.getCategory());
            vo.setExt(a.getExt());
            vo.setFileName(a.getFileName());
            vo.setSizeBytes(a.getSizeBytes());
            vo.setUploaderId(a.getUserId());
            vo.setUploaderNickname(u != null ? u.getNickname() : "已注销用户");
            vo.setUploaderAvatar(u != null ? u.getAvatarUrl() : null);
            vo.setStatus(a.getStatus());
            vo.setRejectReason(publicView ? null : a.getRejectReason());
            vo.setReviewTime(publicView ? null : a.getReviewTime());
            vo.setViewCount(nz(a.getViewCount()));
            vo.setDownloadCount(nz(a.getDownloadCount()));
            vo.setLikeCount(nz(a.getLikeCount()));
            vo.setFavoriteCount(nz(a.getFavoriteCount()));
            vo.setMyLike(!publicView && finalLiked.contains(a.getId()));
            vo.setMyFavorite(!publicView && finalFavorited.contains(a.getId()));
            vo.setCanDelete(!publicView && viewerId > 0 && a.getUserId() == viewerId);
            vo.setCreateTime(a.getCreateTime());
            return vo;
        }).toList();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
