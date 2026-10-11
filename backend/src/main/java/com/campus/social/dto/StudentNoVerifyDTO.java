package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 学号认证（F2 冷启动人工核验兜底）。
 *
 * <p>与校园邮箱认证的差别：邮箱是自证（域名能收信即证明归属），
 * 学号是「提交待核验」，无法在请求内判定真伪，必须进人工队列。
 *
 * <p>格式护栏：学号编码规则由 5 个真实样本锁定——前 2 位入学年、
 * 3-6 位专业类代码、7-8 位专业序号、9-10 位班级、11+ 位流水号(3~4 位)，
 * 总长 13~14 位纯数字。这里只拦「明显乱填」，年份是否在有效范围由
 * service 层按当前年份动态校验(见 CampusVerifyServiceImpl#submitStudentNo)。
 */
@Data
public class StudentNoVerifyDTO {

    @NotBlank(message = "学号不能为空")
    @Pattern(regexp = "^\\d{13,14}$", message = "学号需为 13~14 位数字")
    private String studentNo;

    /**
     * 姓名后 1 到 2 位，供管理员人工比对。
     *
     * <p>契约写 maxLength 4，但注释说 1 到 2 位。取 4 是为了兼容
     * 「复姓 + 单名」这类边界（如「欧阳娜娜」的「娜娜」），
     * 服务端只在非空时校验，不强制恰好 1-2 位。
     */
    @NotBlank(message = "请填写姓名后 1 到 2 位")
    @Size(max = 4, message = "姓名后最多 4 位")
    private String nameTail;
}