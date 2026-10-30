package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 校园信息状态（F2，2026-10-30 降级为资料完整度）。
 *
 * <p>不再下发「能不能搭话/发公告」这类能力位：校园信息不参与权限判定。
 * 搭话看账号年龄+信誉，公告看管理员角色，均不在本 VO 里表达。
 */
@Data
@Builder
public class VerifyStatusVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 0未填写 1已登记 2核对中 3需修改 */
    private Integer verifyStatus;

    private String verifyStatusText;

    private Long schoolId;

    /** 已登记时返回学校名；未填写为 null */
    private String schoolName;

    /** 登记时间；未登记为 null */
    private java.time.LocalDateTime verifiedAt;

    /** verifyStatus=3 时的修改理由 */
    private String rejectReason;

    /** 引导第 1 步是否完成（dept 与 grade 均已填） */
    private Boolean profileComplete;

    /**
     * 本人已提交的学号(未提交为 null)。
     *
     * <p>为什么放这里而不是 {@code UserVO}:UserVO 同时用于关注列表、
     * 搜索用户等多人场景,把学号塞进去等于把它广播给所有人。
     * 学号是隐私(逐页设计明确「不显示真实姓名与学号」),
     * 只有本人接口可以下发——设置页要靠它回填学号框。
     */
    private String studentNo;
}