package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 校园限时活动集市：一条「限时投放的帖子」。
 *
 * <p>与既有 {@link Activity}（圈子签到活动）完全并行、语义不同：
 * 这里是发布一条带到期时间的邀约/求助/结伴信息，广场可见 + 详情页公共留言区。
 * 无审核流程、无对外联系方式入口。
 *
 * <p>广场可见性 = 纯查询过滤（无定时任务）：
 * {@code soft_delete=0 AND is_enable=1 AND is_full=0 AND expire_time>NOW()}，
 * 排序永远按 {@code last_launch_time} 倒序（最近一次激活），不做热度排序。
 * 时间统一东八区（GMT+8）。
 */
@Data
@TableName("mkt_activity")
public class MktActivity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布者 user.id */
    private Long userId;

    /** 一级分类（9选1）：游戏组队/跑腿代办/拼车出行/旅游结伴/学习搭子/运动约伴/干饭搭子/闲置互助/其它 */
    private String category;

    /** 子关键词（可选） */
    private String subTag;

    /** 活动标题（15–25 字符） */
    private String title;

    /** 详细描述（1–500 字） */
    private String content;

    /** 期望参与人数（纯展示，无自动报名/计数） */
    private Integer targetNum;

    /** 费用价格，0=免费 */
    private BigDecimal price;

    /** 活动地点 */
    private String location;

    /** 手动开关 1开 0暂停 */
    private Integer isEnable;

    /** 投放截止时间 */
    private LocalDateTime expireTime;

    /** 手动满员标记 0否 1是 */
    private Integer isFull;

    /** 首次创建时间，固定不变 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 最近一次激活/重投放，广场排序键 */
    private LocalDateTime lastLaunchTime;

    /** 软删（列名 soft_delete，逻辑删除映射到 isDeleted 字段） */
    @TableLogic
    @TableField("soft_delete")
    private Integer isDeleted;
}
