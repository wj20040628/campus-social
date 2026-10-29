package com.campus.social.util;

import java.util.List;

/**
 * 文件分类字典（2026-10-27）。
 *
 * <p>存 code 不存展示名：分类文案会改，历史数据不该跟着迁移。
 * 前端有一份同名的展示映射（constants/fileCategories.js），
 * 两端只共享 code。
 */
public final class FileCategory {

    public static final String TEMPLATE = "template";
    public static final String COURSEWARE = "courseware";
    public static final String GUIDE = "guide";
    public static final String REPORT = "report";
    public static final String EXAM = "exam";
    public static final String OTHER = "other";

    private static final List<String> ALL = List.of(
            TEMPLATE, COURSEWARE, GUIDE, REPORT, EXAM, OTHER);

    private FileCategory() {
    }

    public static boolean isValid(String code) {
        return code != null && ALL.contains(code);
    }

    public static List<String> all() {
        return ALL;
    }
}
