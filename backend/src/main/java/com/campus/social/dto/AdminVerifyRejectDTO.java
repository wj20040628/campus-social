package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 驳回学号核验（openapi AdminVerifyRejectRequest）。
 *
 * <p>{@code reason} 必填且限 200 字，两端都卡：
 * <ul>
 *   <li>必填——用户被驳回后要靠这句话知道改哪里。
 *       留空的后果是他反复提交同样的错误信息，管理员反复驳回，
 *       双方都不知道问题在哪，这是最能消耗耐心的一种交互</li>
 *   <li>上限 200——理由写进 {@code audit_log.detail}，
 *       该列宽有限，且手机上超过 200 字的理由没有人会读完</li>
 * </ul>
 *
 * <p>用 {@code @NotBlank} 而非 {@code @NotNull}：只判空会放过纯空白串，
 * 而「   」在管理员看来是有理由、在用户看来是无理由，这种错位最伤。
 */
@Data
public class AdminVerifyRejectDTO {

    @NotBlank(message = "驳回理由不能为空")
    @Size(max = 200, message = "驳回理由最多 200 字")
    private String reason;
}