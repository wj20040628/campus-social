package com.campus.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起校园邮箱认证（F2）。
 *
 * <p>{@code @Email} 只做格式校验，不判断域名是否为校园邮箱——
 * 那是业务规则（{@code campus.email-domain-whitelist}），在 service 层判。
 * 把业务规则塞进 Bean Validation 会让「白名单为空时该拒还是该放」这种
 * 部署相关的判断写死在注解里。
 */
@Data
public class CampusEmailSendDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;
}