package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 访客访问记录（2026-11-14）。
 *
 * <p>一个浏览器会话一行：进入时插入，之后心跳只更新 last_seen_at 与
 * active_seconds，不按分钟追加行。游客没有账号，visitor_id 是浏览器
 * 本地生成的随机 UUID，不是设备指纹，不含身份信息。
 */
@Data
@TableName("visit_log")
public class VisitLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String visitorId;

    private String sessionId;

    /** 1=游客会话 0=其他 */
    private Integer isGuest;

    /** 入口路径，线下二维码固定为 /guest */
    private String entryPath;

    /** qr / guest_login / direct，或二维码链接的 from 参数 */
    private String source;

    private String ip;

    private String ua;

    private LocalDateTime enteredAt;

    private LocalDateTime lastSeenAt;

    /** 页面在前台的累计秒数，服务端按进入时长做上限约束 */
    private Integer activeSeconds;

    /** 1=已收到结束上报 */
    private Integer ended;
}