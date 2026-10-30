package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 推荐关注的人 —— mapper 层行投影（openapi RecommendUserItem 的超集）。
 *
 * <p>与 {@link RecommendUserVO} 分开的原因：这一层多带了 {@code score}（命中规则的原始打分），
 * 它只用于服务层生成人话理由，不该原样出现在 HTTP 响应里——
 * 把内部排序依据暴露给前端，前端就会开始依赖它渲染排序，
 * 改打分规则时就得同步改前端。
 */
@Data
public class RecommendUserRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    private String avatarUrl;

    private String dept;

    private String grade;

    private Integer followerCount;

    /** 与我的共同兴趣标签数，由一条相关子查询算出，避免服务层 N+1 */
    private Integer commonTagCount;

    /** 命中规则打分（同院系 4 > 同年级 2），服务层据此生成 reason */
    private Integer score;
}
