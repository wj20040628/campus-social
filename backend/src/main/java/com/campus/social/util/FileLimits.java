package com.campus.social.util;

/**
 * 文件上传档位（2026-10-27）。
 *
 * <p>与信誉分共用同一条档位线：60 / 80 / 100。未认证或低于 60 分不可上传。
 * 单文件与总量都按档位递减，和设计评审确认的口径一致：
 * <pre>
 *   1 档 60–79：单文件 ≤1MB，总量 5MB
 *   2 档 80–99：单文件 ≤2MB，总量 10MB
 *   3 档 100  ：单文件 ≤3MB，总量 15MB
 * </pre>
 *
 * <p>把数字放在这里而不是散在 Service 里：上传校验、配额接口、
 * 前端提示都读同一份，避免「页面写 15MB、后端只收 10MB」的静默不一致。
 */
public final class FileLimits {

    public static final int TIER1_MIN_SCORE = 60;
    public static final int TIER2_MIN_SCORE = 80;
    public static final int TIER3_MIN_SCORE = 100;

    private static final long MB = 1024L * 1024L;

    /** 单文件硬上限（最高档），multipart 读取前的第一道保护 */
    public static final long MAX_SINGLE_BYTES = 3 * MB;

    private FileLimits() {
    }

    /** 0 = 不可上传；1/2/3 = 档位 */
    public static int tier(int score, boolean verified) {
        if (!verified || score < TIER1_MIN_SCORE) {
            return 0;
        }
        if (score >= TIER3_MIN_SCORE) {
            return 3;
        }
        return score >= TIER2_MIN_SCORE ? 2 : 1;
    }

    public static long singleLimitBytes(int tier) {
        return switch (tier) {
            case 3 -> 3 * MB;
            case 2 -> 2 * MB;
            case 1 -> 1 * MB;
            default -> 0L;
        };
    }

    public static long totalLimitBytes(int tier) {
        return switch (tier) {
            case 3 -> 15 * MB;
            case 2 -> 10 * MB;
            case 1 -> 5 * MB;
            default -> 0L;
        };
    }

    /** 人类可读的 MB 文案，错误提示用 */
    public static String mbText(long bytes) {
        if (bytes <= 0) {
            return "0MB";
        }
        long mb = bytes / MB;
        return bytes % MB == 0 ? mb + "MB" : String.format("%.1fMB", bytes / (double) MB);
    }
}
