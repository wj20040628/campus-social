package com.campus.social.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 对象存储配置（OSS 接入 P1 基础设施）。
 *
 * <p>所有密钥走环境变量占位（${OSS_*}），绝不硬编码；本地开发填 application-local.yml
 * （已 .gitignore，不进仓库）。OSS SDK 用外网域名生成签名 URL 给浏览器加载；
 * 内网域名留给 ECS 后端拉文件头 / 做回调魔数校验，免流量费。
 */
@Component
@ConfigurationProperties(prefix = "app.oss")
public class OssProperties {

    /** 存储桶名（全局唯一，建桶时定死） */
    private String bucket = "yousheng-uploads";

    /** 地域（华东1 杭州，须与 ECS 同地域，内网免流量费） */
    private String region = "cn-hangzhou";

    /** 外网域名：前端直传 / 浏览器读签名 URL 用 */
    private String endpoint = "oss-cn-hangzhou.aliyuncs.com";

    /** 内网域名：ECS 后端拉头 / 回调校验用，免流量费 */
    private String internalEndpoint = "oss-cn-hangzhou-internal.aliyuncs.com";

    /** STS 扮演的 RAM 角色 ARN（oss-uploader-role） */
    private String roleArn = "";

    /** STS 服务接入点（地域级） */
    private String stsEndpoint = "sts.cn-hangzhou.aliyuncs.com";

    /** OSS 上传回调地址（生产须备案域名 yousheng8.cn；本地 dev 不验证回调） */
    private String callbackUrl = "";

    /** OSS 回调验签密钥（32 位 hex），生产 env 注入 */
    private String callbackKey = "";

    /** 是否开启前端直传 OSS（STS）。关时前端走 POST /upload 服务端兜底。
     *  备案过审前必须关：直传依赖 OSS 回调到「已备案」域名 yousheng8.cn，
     *  备案中该回调不通，直传会被 OSS 判失败。服务端兜底直传 OSS 不依赖回调域名，备案中可用。 */
    private boolean directUploadEnabled = false;

    /** 凭据类型：ak=固定 AccessKey；ecs_ram_role=ECS 实例 RAM 角色（无 AK，SDK 自动轮换） */
    private String credentialType = "ak";

    /** 实例 RAM 角色名（可空：空则从实例元数据自动发现；一台 ECS 只绑一个角色） */
    private String ecsRoleName = "";

    /** 上传 STS 临时令牌用的固定 AK（仅后端，不下发前端） */
    private String stsAccessKeyId = "";

    /** 上传 STS 临时令牌用的固定 SK（仅后端） */
    private String stsAccessKeySecret = "";

    /** 后端生成读签名 URL 用的 AK（只 GetObject） */
    private String readAccessKeyId = "";

    /** 后端生成读签名 URL 用的 SK（只 GetObject） */
    private String readAccessKeySecret = "";

    /** 签名读 URL 有效期（秒） */
    private int presignTtlSeconds = 3600;

    /**
     * STS 临时令牌有效期（秒），需 <= 角色最大会话时长（默认 3600）。
     * 越短越安全：泄露的令牌窗口小，但前端需更频繁重取。
     */
    private int stsDurationSeconds = 3600;

    /** 对象 key 前缀；DB 仍存 /uploads/... 形态，读时映射回 OSS key */
    private String keyPrefix = "uploads";

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getInternalEndpoint() {
        return internalEndpoint;
    }

    public void setInternalEndpoint(String internalEndpoint) {
        this.internalEndpoint = internalEndpoint;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getStsEndpoint() {
        return stsEndpoint;
    }

    public void setStsEndpoint(String stsEndpoint) {
        this.stsEndpoint = stsEndpoint;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getCallbackKey() {
        return callbackKey;
    }

    public void setCallbackKey(String callbackKey) {
        this.callbackKey = callbackKey;
    }

    public boolean isDirectUploadEnabled() {
        return directUploadEnabled;
    }

    public void setDirectUploadEnabled(boolean directUploadEnabled) {
        this.directUploadEnabled = directUploadEnabled;
    }

    public String getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(String credentialType) {
        this.credentialType = credentialType;
    }

    public String getEcsRoleName() {
        return ecsRoleName;
    }

    public void setEcsRoleName(String ecsRoleName) {
        this.ecsRoleName = ecsRoleName;
    }

    public String getStsAccessKeyId() {
        return stsAccessKeyId;
    }

    public void setStsAccessKeyId(String stsAccessKeyId) {
        this.stsAccessKeyId = stsAccessKeyId;
    }

    public String getStsAccessKeySecret() {
        return stsAccessKeySecret;
    }

    public void setStsAccessKeySecret(String stsAccessKeySecret) {
        this.stsAccessKeySecret = stsAccessKeySecret;
    }

    public String getReadAccessKeyId() {
        return readAccessKeyId;
    }

    public void setReadAccessKeyId(String readAccessKeyId) {
        this.readAccessKeyId = readAccessKeyId;
    }

    public String getReadAccessKeySecret() {
        return readAccessKeySecret;
    }

    public void setReadAccessKeySecret(String readAccessKeySecret) {
        this.readAccessKeySecret = readAccessKeySecret;
    }

    public int getPresignTtlSeconds() {
        return presignTtlSeconds;
    }

    public void setPresignTtlSeconds(int presignTtlSeconds) {
        this.presignTtlSeconds = presignTtlSeconds;
    }

    public int getStsDurationSeconds() {
        return stsDurationSeconds;
    }

    public void setStsDurationSeconds(int stsDurationSeconds) {
        this.stsDurationSeconds = stsDurationSeconds;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
