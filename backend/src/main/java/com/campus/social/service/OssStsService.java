package com.campus.social.service;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.campus.social.config.OssProperties;
import org.springframework.stereotype.Service;

/**
 * OSS 直传 STS 临时令牌发放（OSS 接入 P1 基础设施）。
 *
 * <p>复用项目已有的 {@code credentials-java}（type=ram_role_arn）调 STS AssumeRole，
 * 避免再引 sts20150401 且绕开 Java 17 的 javax.xml.bind 坑。
 *
 * <p>令牌 scope 锁在 {@code uploads/{userId}/*}：即使 AK 泄露，对方也只能换到写
 * 该用户自己前缀的临时令牌，动不了别的桶、删不了文件（策略无 DeleteObject）。
 */
@Service
public class OssStsService {

    private final OssProperties props;

    public OssStsService(OssProperties props) {
        this.props = props;
    }

    /**
     * 取当前登录用户的上传临时令牌。
     *
     * @param userId 登录用户 ID，决定写入前缀 uploads/{userId}/*
     * @return 临时 AK / SK / SecurityToken / 过期时间戳（epoch 毫秒）
     */
    public StsToken getStsToken(Long userId) {
        Config config = new Config()
                .setType("ram_role_arn")
                .setAccessKeyId(props.getStsAccessKeyId())
                .setAccessKeySecret(props.getStsAccessKeySecret())
                .setRoleArn(props.getRoleArn())
                .setRoleSessionName("user-" + userId)
                .setPolicy(buildPolicy(userId))
                .setRoleSessionExpiration(props.getStsDurationSeconds())
                .setSTSEndpoint(props.getStsEndpoint());
        CredentialModel cred = new Client(config).getCredential();
        return new StsToken(cred.getAccessKeyId(), cred.getAccessKeySecret(),
                cred.getSecurityToken(), cred.getExpiration());
    }

    /**
     * 前端直传的最小授权策略：仅允许写 uploads/{userId}/*，
     * 动作限 PutObject / AbortMultipartUpload / ListParts（分片配套），
     * 无 GetObject / DeleteObject / ListObjects。
     */
    private String buildPolicy(Long userId) {
        String resource = "acs:oss:*:*:" + props.getBucket()
                + "/" + props.getKeyPrefix() + "/" + userId + "/*";
        return "{\"Version\":\"1\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"oss:PutObject\",\"oss:AbortMultipartUpload\",\"oss:ListParts\"],"
                + "\"Resource\":\"" + resource + "\"}]}";
    }

    /** STS 临时凭据（accessKeyId / accessKeySecret / securityToken / expiration epoch 毫秒） */
    public record StsToken(String accessKeyId, String accessKeySecret, String securityToken, long expiration) {
    }
}
