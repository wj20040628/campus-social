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
     * 姓名后 1 到 2 位（选填），供管理员人工比对。
     *
     * <p>2026-10-27 起不再强制填写：用户反馈这项收集没有必要，
     * 认证主判据是学号与学校归属的六层比对。前端已移除该输入框，
     * 这里保留字段以兼容旧客户端；为空时审计日志不写姓名后缀。
     */
    @Size(max = 4, message = "姓名后最多 4 位")
    private String nameTail;
}