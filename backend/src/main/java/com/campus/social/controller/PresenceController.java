package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.PresenceHiddenDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.PresenceService;
import com.campus.social.vo.PresenceStatsVO;
import com.campus.social.vo.PresenceStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在线状态（2026-11-13）。
 *
 * <p>头像绿点走批量查询，避免每个头像各发一次请求；顶栏人数走公共统计，
 * 未登录也能取。隐身开关只影响头像绿点是否对外展示；在线总数按实际连接
 * 统计（含隐身），变化时由 PresenceStatsPushTask 主动推送。
 */
@RestController
@RequiredArgsConstructor
public class PresenceController {

    private static final int MAX_QUERY_IDS = 100;

    private final PresenceService presenceService;

    @GetMapping("/presence/status")
    public Result<PresenceStatusVO> status(@RequestParam("ids") String ids) {
        return Result.ok(presenceService.status(parseIds(ids)));
    }

    @GetMapping("/presence/stats")
    public Result<PresenceStatsVO> stats() {
        return Result.ok(presenceService.stats());
    }

    @PutMapping("/user/presence")
    public Result<Map<String, Boolean>> setHidden(@Valid @RequestBody PresenceHiddenDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        boolean hidden = presenceService.setHidden(me, Boolean.TRUE.equals(dto.getHidden()));
        return Result.ok(Map.of("hidden", hidden));
    }

    /**
     * 逗号分隔的 id 串。非法片段跳过而不是整体 400：前端批量查询是尽力而为，
     * 一个脏 id 不该让整页头像都失去在线点。
     */
    private List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 跳过非法 id
            }
            if (ids.size() >= MAX_QUERY_IDS) {
                break;
            }
        }
        return ids;
    }
}
