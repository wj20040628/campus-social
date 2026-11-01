package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 集市内容规则（2026-11-01 从 MktActivityServiceImpl 拆出）。
 *
 * <p>分类白名单、标题派生、文本长度与投放时长校验都是无状态纯函数；
 * 写入与 VO 装配共用同一份实现，避免「发布时按 12h 校验、展示时按另一套推导」。
 */
final class MktActivityRules {

    /** 一级分类白名单（9 选 1）。运营维护，与前端发布页下拉一致。 */
    static final Set<String> CATEGORIES = Set.of(
            "游戏组队", "跑腿代办", "拼车出行", "旅游结伴", "学习搭子",
            "运动约伴", "干饭搭子", "闲置互助", "其它");

    /** 单次投放时长上限。 */
    private static final int MAX_LAUNCH_HOURS = 12;

    private MktActivityRules() {
    }

    static void validateCategory(String category) {
        if (category == null || !CATEGORIES.contains(category.trim())) {
            throw new BusinessException(ResultCode.MKT_CATEGORY_INVALID);
        }
    }

    /** 空串归一为 null（sub_tag / location 等可选字段）。 */
    static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 取非空文本并做长度硬校验。
     * 中英文各计 1 字符（Java {@code String.length} 即 UTF-16 码元数，对常见中文/ASCII 等价）。
     */
    static String requireText(String raw, ResultCode rc, int min, int max) {
        if (raw == null) {
            throw new BusinessException(rc);
        }
        String t = raw.trim();
        if (t.length() < min || t.length() > max) {
            throw new BusinessException(rc);
        }
        return t;
    }

    /**
     * 标题(可选):前端不再采集标题,主文案由「分类 · 游戏」派生。
     * 传入非空且 ≤25 字则保留(trim 后),留空/未传/超长一律置 null。
     */
    static String optionalTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 25 ? t.substring(0, 25) : t;
    }

    /**
     * 派生主文案「分类 · 游戏」:标题列 NOT NULL 的兜底,同时是卡片/详情的主文案来源。
     * 游戏组队且有游戏名 →「游戏组队 · 王者荣耀」;否则只给分类。限 64 列宽内。
     */
    static String derivedTitle(String category, String subTag) {
        String cat = category == null ? "" : category.trim();
        String game = subTag == null ? "" : subTag.trim();
        String main = game.isEmpty() ? cat : cat + " · " + game;
        return main.length() > 64 ? main.substring(0, 64) : main;
    }

    /**
     * 投放截止时间校验：now < expire <= now + MAX_LAUNCH_HOURS。
     * 既拦「过去时间」也拦「单次超 12h」——防前端篡改。
     */
    static LocalDateTime validateExpire(LocalDateTime expire) {
        if (expire == null) {
            throw new BusinessException(ResultCode.MKT_EXPIRE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        // 用秒数比较,不用 toHours():Duration.toHours() 向下取整,12h 整点会因
        // 网络/请求耗时变成 11h59m 而取整成 11,导致「12 小时」快捷选项偶发被拦。
        if (!expire.isAfter(now)
                || Duration.between(now, expire).toSeconds() > MAX_LAUNCH_HOURS * 3600L) {
            throw new BusinessException(ResultCode.MKT_EXPIRE_INVALID);
        }
        return expire;
    }
}
