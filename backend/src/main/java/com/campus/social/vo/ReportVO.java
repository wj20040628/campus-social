package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端举报列表行（F4，AC-14 手机端可用）。
 *
 * <p>OD-19 裁定后台也要在手机上可用，故单行字段保持精简：
 * {@code reasonText} 超过 60 字截断并用 {@code reasonTextTruncated} 标记，
 * 不让一段长文把 375px 视口撑到需要横向滚动才能读完。
 */
@Data
@Builder
public class ReportVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long reporterId;

    private String reporterNickname;

    /** 1用户 2帖子 3评论 4聊天消息 5搭子 6集市活动 7文件 */
    private Integer targetType;

    private Long targetId;

    /** 被举报对象的摘要，供管理员在一屏内判断 */
    private String targetSummary;

    /**
     * 被举报内容的作者（2026-10-24 加）。targetType=1（举报用户）时即被举报人本身。
     * 管理员处置的核心依据是「谁在发」——只给摘要不给作者，
     * "666" 这种摘要等于让人盲判。可空：内容已物理删除时查不到。
     */
    private Long targetAuthorId;

    /** 作者昵称（与 targetAuthorId 同批解析）；查不到用户时为 null */
    private String targetAuthorNickname;

    /**
     * 举报提交时的目标快照（2026-10-29 加），已渲染成可读文本。
     * 原内容被作者删除后，管理员仍能据此看到当时的正文/作者/媒体。
     */
    private String targetSnapshotText;

    /** 1骚扰 2广告 3色情 4诈骗 5其他 */
    private Integer reasonType;

    /** 超过 60 字截断 */
    private String reasonText;

    /** reasonText 是否被截断过，前端据此给「展开」入口 */
    private Boolean reasonTextTruncated;

    /** 0待审 1成立 2驳回 */
    private Integer status;

    private Long handlerId;

    /**
     * 处置备注（2026-10-28 补）。
     *
     * <p>ReportListVO 一直在查这一列，但 VO 没接——「已处理」列表的
     * 「处置说明」行因此永远不渲染。管理员处置完再也查不到自己写了什么。
     */
    private String handleNote;

    private LocalDateTime handleTime;

    private LocalDateTime createTime;
}