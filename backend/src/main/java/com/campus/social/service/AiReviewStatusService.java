package com.campus.social.service;

import com.campus.social.vo.AiReviewStatusVO;

/** AI 机审配置自检（2026-11-08）。 */
public interface AiReviewStatusService {

    /** 返回可安全下发的配置状态；不含 API Key。 */
    AiReviewStatusVO status();
}
