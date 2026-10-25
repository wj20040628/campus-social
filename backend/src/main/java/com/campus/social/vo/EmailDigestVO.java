package com.campus.social.vo;

import lombok.Data;

import java.util.List;

/**
 * 邮件摘要订阅（GET/PUT /user/email-digest，2026-10-25）。
 *
 * <p>每天最多一封（18:00），且仅当订阅类型有新动态时发送。
 * types 取值：chat（新搭话）/ comment（评论与@你）/ like（被赞）/
 * follow（新粉丝）/ system（活动与审核结果）。
 * 默认关闭——邮件是 opt-in 的打扰。
 */
@Data
public class EmailDigestVO {

    /** 是否开启每日摘要邮件 */
    private Boolean enabled;

    /** 订阅的类型组 key 列表；空列表 = 开着但什么都不订（不会发信） */
    private List<String> types;
}
