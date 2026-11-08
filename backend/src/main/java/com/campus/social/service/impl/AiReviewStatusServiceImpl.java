package com.campus.social.service.impl;

import com.campus.social.service.AiReviewStatusService;
import com.campus.social.vo.AiReviewStatusVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 llm.* 配置并给出可行动提示。
 *
 * <p>只读配置，不探活、不发请求：状态页刷新频繁，探活会反复消耗额度；
 * 真正的调用失败会在具体任务的 ai_reason 里留下原因。
 */
@Service
public class AiReviewStatusServiceImpl implements AiReviewStatusService {

    @Value("${llm.enabled:false}")
    private boolean enabled;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.provider:claude}")
    private String provider;

    @Value("${llm.model:claude-sonnet-5}")
    private String model;

    @Value("${llm.base-url:}")
    private String baseUrl;

    @Override
    public AiReviewStatusVO status() {
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        boolean ready = enabled && keyConfigured;

        AiReviewStatusVO vo = new AiReviewStatusVO();
        vo.setEnabled(enabled);
        vo.setKeyConfigured(keyConfigured);
        vo.setProvider(provider);
        vo.setModel(model);
        vo.setBaseUrlConfigured(baseUrl != null && !baseUrl.isBlank());
        vo.setReady(ready);
        vo.setHint(hint(enabled, keyConfigured));
        return vo;
    }

    private String hint(boolean enabled, boolean keyConfigured) {
        if (!enabled) {
            return "未启用：在服务器环境变量设置 LLM_ENABLED=true 并重启后端";
        }
        if (!keyConfigured) {
            return "缺密钥：设置 LLM_API_KEY（只放环境变量，不要写进仓库）后重启后端";
        }
        return "已启用：" + provider + " / " + model
                + "；只审核命中敏感词的内容，调用失败会自动转人工";
    }
}
