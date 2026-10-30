package com.campus.social.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
     * 对同一陌生人每天最多发多少条（2026-10-28 改为 1）。
     *
     * <p>不再限制「每天最多向几个新对象发起」：对象数量本身不是风险，
     * 对单个对象的骚扰频率才是。对方关注你或回复你之后解除限制。
     */
    private int perPeerDailyLimit = 1;

    /** 引导要求最少选几个标签 */
    private int onboardingMinTags = 3;

    /** 引导要求最多选几个标签 */
    private int onboardingMaxTags = 5;

    /** 兴趣标签上限（PUT /user/tags 与引导共用） */
    private int maxTags = 8;

    /**
     * 单个群的最大成员数(拉人时校验)。校园场景 500 足够,
     * 防群被拉成超大群导致写扩散/未读计数膨胀。配置可改,不必发版。
     */
    private int groupMaxMembers = 500;

    /**
     * 行为信任门槛（2026-10-30）。
     *
     * <p>校园信息不再作为功能门槛后，陌生人搭话、建圈、建群、集市发布
     * 改由「账号注册时间 + 信誉分」判定。阈值放配置而不是写死：
     * 上线后按举报率调整，不必重新发版。
     */
    private int accessStrangerChatMinHours = 24;
    private int accessCreateCircleMinDays = 3;
    private int accessCreateMktMinDays = 1;
    private int accessCreateGroupMinDays = 3;
    private int accessActivitySignupMinDays = 3;
    private int accessMinCredit = 60;

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

    public int getGroupMaxMembers() {
        return groupMaxMembers;
    }

    public void setGroupMaxMembers(int groupMaxMembers) {
        this.groupMaxMembers = groupMaxMembers;
    }

    public int getAccessStrangerChatMinHours() {
        return accessStrangerChatMinHours;
    }

    public void setAccessStrangerChatMinHours(int accessStrangerChatMinHours) {
        this.accessStrangerChatMinHours = accessStrangerChatMinHours;
    }

    public int getAccessCreateCircleMinDays() {
        return accessCreateCircleMinDays;
    }

    public void setAccessCreateCircleMinDays(int accessCreateCircleMinDays) {
        this.accessCreateCircleMinDays = accessCreateCircleMinDays;
    }

    public int getAccessCreateMktMinDays() {
        return accessCreateMktMinDays;
    }

    public void setAccessCreateMktMinDays(int accessCreateMktMinDays) {
        this.accessCreateMktMinDays = accessCreateMktMinDays;
    }

    public int getAccessCreateGroupMinDays() {
        return accessCreateGroupMinDays;
    }

    public void setAccessCreateGroupMinDays(int accessCreateGroupMinDays) {
        this.accessCreateGroupMinDays = accessCreateGroupMinDays;
    }

    public int getAccessActivitySignupMinDays() {
        return accessActivitySignupMinDays;
    }

    public void setAccessActivitySignupMinDays(int accessActivitySignupMinDays) {
        this.accessActivitySignupMinDays = accessActivitySignupMinDays;
    }

    public int getAccessMinCredit() {
        return accessMinCredit;
    }

    public void setAccessMinCredit(int accessMinCredit) {
        this.accessMinCredit = accessMinCredit;
    }
}
