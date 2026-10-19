package com.campus.social.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交意见反馈（2026-10-18）。
 *
 * <p>{@code content} 上限 1000 字，与 {@code feedback.content} 列宽一致。
 * 取 1000 而不是举报那样的 200：反馈是「描述问题」而非「选一个原因」，
 * 复现步骤 + 现象 + 期望三段写下来，200 字往往只够写半句。
 *
 * <p>媒体走 {@code List<MediaItem>} 而不是裸字符串：前端传结构化 JSON，
 * 由 Jackson 序列化落库。校验放在这里（数量、类型、地址长度），
 * 后端不校验 url 指向的内容 —— 它只是个 OSS 相对路径，
 * 真实性由上传接口保证，反查一次 OSS 只会让提交反馈变慢。
 */
@Data
public class FeedbackSubmitDTO {

    /** 1功能建议 2使用问题 3内容问题 4界面体验 5其他 */
    @NotNull(message = "请选择反馈分类")
    @Min(value = 1, message = "反馈分类不合法")
    @Max(value = 5, message = "反馈分类不合法")
    private Integer category;

    @NotBlank(message = "请描述一下遇到的问题或建议")
    @Size(max = 1000, message = "描述最多 1000 字")
    private String content;

    /** 图片 / 视频附件，可空。最多 9 个，与发帖媒体上限一致 */
    @Valid
    @Size(max = 9, message = "附件最多 9 个")
    private List<MediaItem> media;

    /**
     * 媒体项：只认 type 与 url 两个字段，多余字段由 Jackson 默认忽略。
     *
     * <p>type 与发帖媒体同码：1 图片 / 2 视频。前端 {@code useMediaUpload}
     * 的 readyMedia 用的就是 img / video 字符串，转换只在这一层做。
     */
    public static class MediaItem {

        @NotNull(message = "媒体类型不能为空")
        @Min(value = 1, message = "媒体类型不合法")
        @Max(value = 2, message = "媒体类型不合法")
        private Integer type;

        @NotBlank(message = "媒体地址不能为空")
        @Size(max = 500, message = "媒体地址过长")
        private String url;

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
