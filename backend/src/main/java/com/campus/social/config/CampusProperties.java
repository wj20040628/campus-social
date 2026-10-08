package com.campus.social.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 校园信息与陌生人搭话护栏配置（SPEC §5.1 / ARCHITECTURE §3 Q9）。
 *
 * <p>取值全部来自配置文件与环境变量，<b>不硬编码</b>：
 * 配额值写死在代码里等于「改一次要发一次版」，而护栏参数恰恰是需要按运营反馈调整的。
 */
@Component
@ConfigurationProperties(prefix = "campus")
public class CampusProperties {

    /** 学校名（MVP 单校，不建 school 表——为一行数据建表属过度设计） */
    private String schoolName = "本校";

    /**
     * 学校 ID（MVP 单校恒为 1，与 {@code user.school_id} / {@code announcement.school_id} 对齐）。
     *
     * <p>做成配置而不是各处硬编码 1：多校预留字段已经在库里了，
     * 到真要开第二个学校时，只改这一处配置即可，不必翻遍所有 SQL 找硬编码的 1。
     */
    private long schoolId = 1L;

    /**
     * 校园邮箱域名白名单，逗号分隔（如 {@code mail.xxx.edu.cn,xxx.edu.cn}）。
     * 不进数据库：白名单是部署级配置，改一次的频率远低于建表 + 缓存 + 刷新接口的成本。
     */
    private String emailDomainWhitelist = "";

    /** 每天最多可向几个新对象发起搭话 */
    private int dailyNewPeerLimit = 5;

    /** 对同一陌生人每天最多发多少条 */
    private int perPeerDailyLimit = 20;

    /** 引导要求最少选几个标签 */
    private int onboardingMinTags = 3;

    /** 引导要求最多选几个标签 */
    private int onboardingMaxTags = 5;

    /** 兴趣标签上限（PUT /user/tags 与引导共用） */
    private int maxTags = 8;

    public String getSchoolName() {
        return schoolName;
    }

    public void setSchoolName(String schoolName) {
        this.schoolName = schoolName;
    }

    public long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(long schoolId) {
        this.schoolId = schoolId;
    }

    public String getEmailDomainWhitelist() {
        return emailDomainWhitelist;
    }

    public void setEmailDomainWhitelist(String emailDomainWhitelist) {
        this.emailDomainWhitelist = emailDomainWhitelist;
    }

    public int getDailyNewPeerLimit() {
        return dailyNewPeerLimit;
    }

    public void setDailyNewPeerLimit(int dailyNewPeerLimit) {
        this.dailyNewPeerLimit = dailyNewPeerLimit;
    }

    public int getPerPeerDailyLimit() {
        return perPeerDailyLimit;
    }

    public void setPerPeerDailyLimit(int perPeerDailyLimit) {
        this.perPeerDailyLimit = perPeerDailyLimit;
    }

    public int getOnboardingMinTags() {
        return onboardingMinTags;
    }

    public void setOnboardingMinTags(int onboardingMinTags) {
        this.onboardingMinTags = onboardingMinTags;
    }

    public int getOnboardingMaxTags() {
        return onboardingMaxTags;
    }

    public void setOnboardingMaxTags(int onboardingMaxTags) {
        this.onboardingMaxTags = onboardingMaxTags;
    }

    public int getMaxTags() {
        return maxTags;
    }

    public void setMaxTags(int maxTags) {
        this.maxTags = maxTags;
    }

    /** 解析域名白名单为小写 Set，便于大小写不敏感匹配 */
    public Set<String> resolveEmailDomains() {
        Set<String> set = new LinkedHashSet<>();
        if (emailDomainWhitelist == null || emailDomainWhitelist.isBlank()) {
            return set;
        }
        for (String s : emailDomainWhitelist.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }
}
