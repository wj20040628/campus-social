package com.campus.social.service;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;

/**
 * 把 credentials-java 的「ECS 实例 RAM 角色」凭据适配成 OSS SDK 的 CredentialsProvider。
 *
 * <p>与 AliyunDmMailGateway 用的是同一种凭据：从实例元数据自动获取 STS 临时凭证并轮换，
 * 服务器磁盘和环境变量里都不需要 AccessKey。每次 getCredentials 交给 credentials-java
 * 的客户端做缓存/提前刷新，这里不做额外缓存。
 */
final class EcsRamRoleCredentialsProvider implements CredentialsProvider {

    private final Client client;

    EcsRamRoleCredentialsProvider(String roleName) {
        Config config = new Config().setType("ecs_ram_role");
        if (roleName != null && !roleName.isBlank()) {
            config.setRoleName(roleName);
        }
        this.client = new Client(config);
    }

    @Override
    public Credentials getCredentials() {
        CredentialModel cred = client.getCredential();
        return new DefaultCredentials(
                cred.getAccessKeyId(), cred.getAccessKeySecret(), cred.getSecurityToken());
    }

    @Override
    public void setCredentials(Credentials creds) {
        // 实例角色凭据由 SDK 自动轮换，外部设置无意义
    }
}
