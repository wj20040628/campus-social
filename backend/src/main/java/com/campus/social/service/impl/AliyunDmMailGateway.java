package com.campus.social.service.impl;

import com.aliyun.dm20151123.Client;
import com.aliyun.dm20151123.models.SingleSendMailRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.campus.social.service.MessageGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 阿里云邮件推送(DirectMail)网关,走 Tea SDK {@code dm20151123}。
 *
 * <p>激活条件:{@code app.campus.message-gateway=aliyun}(与 dev / prod(SMTP) 三选一,
 * 互斥)。仅在生产决定用阿里云 DM 而非 SMTP 时切换,本地开发仍走 dev 控制台打印。
 *
 * <p>异步发送:与 {@link SmtpMessageGateway} 同构 —— {@code sendCode} 立刻返回 true
 * (验证码已先入 Redis),真实调 DM 接口转后台线程(mailExecutor),避免前端接口
 * 因 DM 网络慢/超时被卡住。后台投递失败时只记日志,验证码仍在 Redis 可校验
 * (与 SMTP 网关一致的「尽力投递」语义)。
 *
 * <p>用途:账号体系重构后的两类邮件 ——
 *   ① 邮箱验证码登录(type=login) ② 找回密码重置验证码(type=reset)。
 * 邮件消耗量极小:大部分人走学号密码登录,只有少数场景才发邮件。
 *
 * <p>注意:发信地址 / 域名需在阿里云 DM 控制台预先验证(发信域名 + 发信地址),
 * 否则 SingleSendMail 会返回 {@code InvalidToAddress} / {@code Unauthorized} 类错误,
 * 由后台日志暴露,不影响主流程(验证码仍在 Redis)。
 *
 * <p>发信内容:优先走控制台「模板管理」已审核模板({@code template-id} 非空时),
 * 内容是受控的、被判垃圾邮件概率低于裸 HtmlBody;模板模式下标题以请求
 * {@code subject} 为准(需与控制台模板「邮件标题」保持一致),正文由模板渲染,
 * 不再直传 HtmlBody。模板 ID 未配置时回退为直传 HtmlBody(本地联调兼容)。
 * 无论哪种模式,本项目均为事务性单发(验证码),不群发。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.campus.message-gateway", havingValue = "aliyun")
public class AliyunDmMailGateway implements MessageGateway {

    private final Client client;
    private final String fromAddress;   // DM 发信地址(AccountName),如 noreply@yousheng8.cn
    private final String fromAlias;     // 发件人别名,如 友声
    private final Integer addressType;  // 0=随机发信地址(推荐) 1=用 fromAddress 发
    private final String tagName;       // 邮件标签(可选,用于 DM 控制台统计)
    /** 控制台「模板管理」已审核邮件模板 ID;非空则走模板发信(忽略 HtmlBody),为空回退直传 HtmlBody */
    private final String templateId;
    /** 邮件标题:模板模式下标题以请求为准(需与控制台模板「邮件标题」保持一致) */
    private final String subject;
    /** 凭据类型:ecs_ram_role(默认,走 ECS 实例元数据自动发/刷 STS,磁盘无 AK)/ access_key(回退环境变量 AK) */
    private final String credentialType;
    /** ECS 实例绑定的 RAM 角色名(可空,空则 SDK 自动从实例元数据发现) */
    private final String roleName;
    /** 自身代理,用于触发 @Async(避免同类内部调用绕过代理) */
    private final AliyunDmMailGateway self;

    public AliyunDmMailGateway(
            @Value("${app.campus.aliyun.access-key-id:}") String accessKeyId,
            @Value("${app.campus.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${app.campus.aliyun.region-id:cn-hangzhou}") String regionId,
            @Value("${app.campus.aliyun.endpoint:dm.aliyuncs.com}") String endpoint,
            @Value("${app.campus.aliyun.from-address:}") String fromAddress,
            @Value("${app.campus.aliyun.from-alias:友声}") String fromAlias,
            @Value("${app.campus.aliyun.address-type:1}") Integer addressType,
            @Value("${app.campus.aliyun.tag-name:}") String tagName,
            @Value("${app.campus.aliyun.template-id:}") String templateId,
            @Value("${app.campus.aliyun.subject:系统验证码通知}") String subject,
            @Value("${app.campus.aliyun.credential-type:ecs_ram_role}") String credentialType,
            @Value("${app.campus.aliyun.role-name:}") String roleName,
            @Lazy AliyunDmMailGateway self) {
        this.fromAddress = fromAddress;
        this.fromAlias = fromAlias;
        this.addressType = addressType;
        this.tagName = tagName;
        this.templateId = templateId;
        this.subject = subject;
        this.credentialType = credentialType;
        this.roleName = roleName;
        this.self = self;

        // 统一凭据客户端:优先 ECS 实例 RAM 角色(元数据自动发、自动刷 STS,磁盘与 env 无 AK);
        // credential-type=access_key 时回退到 access-key-id/secret 环境变量(本地或非 ECS 兜底)。
        // 密钥缺失不影响启动,真正取不到凭据由后台发信日志暴露。
        try {
            com.aliyun.credentials.models.Config credConfig = new com.aliyun.credentials.models.Config();
            if ("access_key".equals(credentialType)) {
                credConfig.setType("access_key");
                credConfig.setAccessKeyId(accessKeyId);
                credConfig.setAccessKeySecret(accessKeySecret);
            } else {
                credConfig.setType("ecs_ram_role");
                if (roleName != null && !roleName.isEmpty()) {
                    credConfig.setRoleName(roleName);
                }
            }
            com.aliyun.credentials.Client credentialClient = new com.aliyun.credentials.Client(credConfig);

            Config config = new Config();
            config.setCredential(credentialClient);
            config.endpoint = endpoint;
            config.regionId = regionId;
            this.client = new Client(config);
            log.info("AliyunDmMailGateway initialized, endpoint={}, fromAddress={}, credentialType={}",
                    endpoint, fromAddress, credConfig.getType());
        } catch (Exception e) {
            throw new IllegalStateException("阿里云 DM 网关初始化失败,请检查凭据(实例 RAM 角色是否已绑定/可达)与 endpoint", e);
        }
    }

    @Override
    public boolean sendCode(String target, String code) {
        if (target == null || !target.contains("@")) {
            // 本网关只发邮件(手机号验证码场景本项目已取消)
            log.warn("AliyunDmMailGateway 仅支持邮箱,忽略非邮箱目标: {}", target);
            return false;
        }
        // 立即返回 true(验证码已在 Redis,用户可继续校验),邮件投递转后台
        self.deliverAsync(target, code);
        log.info("验证码邮件已提交阿里云 DM 后台投递: target={}", target);
        return true;
    }

    /** @Async:Spring 代理在 mailExecutor 线程池上执行真实 DM 调用 */
    @Async("mailExecutor")
    public CompletableFuture<Void> deliverAsync(String target, String code) {
        try {
            deliver(target, code);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("阿里云 DM 邮件投递失败: target={}, err={}", target, e.getMessage(), e);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /** 实际 DM 投递(后台线程执行,不阻塞调用方) */
    private void deliver(String target, String code) throws Exception {
        SingleSendMailRequest req = new SingleSendMailRequest()
                .setAccountName(fromAddress)
                .setAddressType(addressType)
                .setFromAlias(fromAlias)
                .setReplyToAddress(false)
                .setToAddress(target)
                .setSubject(subject);
        if (templateId != null && !templateId.isEmpty()) {
            // 模板发信:内容以控制台已审核模板为准,忽略 HtmlBody,降低反垃圾风控命中率
            req.setTemplate(new SingleSendMailRequest.SingleSendMailRequestTemplate()
                    .setTemplateId(templateId)
                    .setTemplateData(Map.of("code", code)));
        } else {
            // 回退:无模板时直传 HtmlBody(本地联调 / 未配模板时的兼容路径)
            req.setHtmlBody(buildHtmlBody(code));
        }
        if (tagName != null && !tagName.isEmpty()) {
            req.setTagName(tagName);
        }
        req.setClickTrace("0");
        client.singleSendMailWithOptions(req, new RuntimeOptions());
        log.info("阿里云 DM 邮件投递成功: target={}, templateMode={}", target,
                (templateId != null && !templateId.isEmpty()));
    }

    private String buildHtmlBody(String code) {
        return "<div style='font-family:sans-serif;padding:20px;'>"
                + "<h2 style='color:#2b6cb0'>友声</h2>"
                + "<p>您的验证码:</p>"
                + "<div style='font-size:32px;font-weight:bold;letter-spacing:4px;color:#2b6cb0'>" + code + "</div>"
                + "<p style='color:#999;font-size:13px'>验证码 5 分钟内有效,请勿泄露给他人。</p>"
                + "</div>";
    }
}
