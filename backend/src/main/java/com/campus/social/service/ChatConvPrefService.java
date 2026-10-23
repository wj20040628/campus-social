package com.campus.social.service;

import com.campus.social.entity.ChatConvPref;

import java.util.Map;

/**
 * 会话偏好（置顶 / 免打扰 / 删除）。
 *
 * <p>与 ChatService 分开：列表要用它过滤/排序，发消息要用它判断免打扰，
 * 塞进 ChatService 会让那两个 300 行上下的实现类继续膨胀。
 */
public interface ChatConvPrefService {

    /**
     * 取某用户某类型的全部偏好，key = targetId。
     * 没设置过的会话不会出现在 map 里，调用方按「未置顶/未静音/未删除」处理。
     */
    Map<Long, ChatConvPref> loadByKind(long me, String kind);

    /**
     * 设置偏好。三个开关都可空，只更新传了的。
     * 传入全空等于一次无副作用的读，直接返回。
     */
    void set(long me, String kind, long targetId, Boolean pinned, Boolean muted, Boolean hidden);

    /** 是否免打扰（发消息时判要不要推送通知） */
    boolean isMuted(long me, String kind, long targetId);
}
