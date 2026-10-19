package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 圈子名:3-7 个字符,汉字为主,允许混入字母/数字(如「23届网安社团」),
 * 但必须至少含 1 个汉字 —— 纯数字「111」/ 纯字母「abc」/ 纯符号都拒。
 *
 * 为什么必须含汉字:
 * - 纯数字圈名在列表里和成员数「128 成员」撞视觉,用户分不清哪个是名字
 * - 纯字母圈名(「abc」)无检索价值,搜索「a」会拉出一堆无意义结果
 * - 汉字是圈名主载体,「23届」「23级」这种年级前缀才有真实使用场景
 *
 * 全角→半角归一化在 Service 层做(trim + 转半角),DTO 这里只负责格式与长度。
 */
@Data
public class CircleDTO {
    @NotBlank
    @Size(min = 3, max = 7)
    @Pattern(regexp = "^(?![0-9a-zA-Z._-]*$)[\\u4e00-\\u9fa5a-zA-Z0-9._-]{3,7}$",
            message = "圈子名需含汉字(可搭配字母/数字),不能是纯数字/纯字母")
    private String name;
}
