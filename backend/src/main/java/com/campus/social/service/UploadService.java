package com.campus.social.service;

import java.util.Map;

/**
 * 文件上传(本地磁盘)
 * 返回相对 URL(如 /uploads/20260914/xxx.jpg),前端拼 location.origin
 */
public interface UploadService {

    /**
     * 上传并记录归属。
     *
     * @param userId 当前登录用户 id,由 controller 从请求属性取,用于落 upload_file 归属记录
     * @return {@link UploadResult}:原图相对路径恒有;图片且压缩成功时带缩略图相对路径
     * @throws com.campus.social.exception.BusinessException 超限/类型不符
     */
    UploadResult upload(org.springframework.web.multipart.MultipartFile file, String kind, long userId);

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

    /**
     * 前端「能力探测」用:返回当前上传配置。
     *
     * <p>无副作用的只读接口(不进 auth/verified 排除名单也可以,但放公开更省一次登录态往返)。
     * 前端据此判断视频功能开没开、单个视频能传多大:
     * <ul>
     *   <li>{@code videoEnabled=false} → 前端隐藏视频入口</li>
     *   <li>{@code maxVideoBytes} → 前端本地校验,超过就拒,不发无效请求</li>
     * </ul>
     *
     * @return {@code {"videoEnabled":true,"maxVideoBytes":10485760,"maxImageBytes":7864320}}
     */
    Map<String, Object> uploadConfig();
}
