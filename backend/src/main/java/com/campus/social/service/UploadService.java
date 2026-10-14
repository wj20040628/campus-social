package com.campus.social.service;

/**
 * 文件上传(本地磁盘)
 * 返回相对 URL(如 /uploads/20260914/xxx.jpg),前端拼 location.origin
 */
public interface UploadService {

    /**
     * 上传并记录归属。
     *
     * @param userId 当前登录用户 id,由 controller 从请求属性取,用于落 upload_file 归属记录
     * @return 相对访问路径(如 /uploads/xxx.png)
     * @throws com.campus.social.exception.BusinessException 超限/类型不符
     */
    String upload(org.springframework.web.multipart.MultipartFile file, String kind, long userId);

    /**
     * 校验「某头像 URL 是否属于指定用户上传」。
     *
     * <p>用于 {@code updateProfile} / {@code createGroup} 设置头像时:
     * 头像必须引用当前用户上传的 /uploads 文件。兼容老数据:URL 没有归属记录
     * (老数据/外部图床)时放行,只有「URL 在 upload_file 里且 owner != 自己」才拒。
     *
     * @param avatarUrl 头像地址(绝对或相对的 /uploads/... 路径)
     * @return true=允许(自己上传的 / 无归属记录),false=拒(明确是别人的)
     */
    boolean isAvatarAllowed(String avatarUrl, long me);
}
