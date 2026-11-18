package com.campus.social.service.impl;

import com.campus.social.common.ReportTargetType;
import com.campus.social.entity.Report;
import com.campus.social.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 举报成立时，按 targetType 把被举报内容一并下架/隐藏（2026-11-14 从
 * ReportReviewService 拆出）。
 *
 * <p>仅封作者、不下架内容，是举报系统最常见的「判了等于没判」：
 * 用户举报一条色情帖，管理员判成立→作者被封，但那条帖子和它的评论照常展示，
 * 举报人下次还看得到，系统公信力直接归零。
 *
 * <p><b>全部改用 ReportMapper 里的显式 UPDATE，不再 updateById(entity)</b>：
 * 项目配了 MyBatis-Plus 逻辑删除（logic-delete-field: isDeleted），
 * updateById 会主动排除被登记的逻辑删除字段，于是
 * {@code c.setIsDeleted(1); updateById(c)} <b>静默失效</b> ——
 * 日志打「下架成功」、数据库里 is_deleted 仍是 0、内容照常展示。
 * 现在每条都是显式 UPDATE，返回值是真实影响行数，
 * 「已下架过」与「刚下架」能区分开。
 */
@Service
@RequiredArgsConstructor
public class ReportContentTakedown {

    private final ReportMapper reportMapper;

    /**
     * 下架被举报内容。
     *
     * @return 被下架内容的简短描述（用于审计与日志）；无内容可下架时返回 null
     */
    public String takedown(Report report) {
        return switch (report.getTargetType()) {
            case ReportTargetType.POST -> { // status 1→2 下架(与机审驳回同口径)
                yield reportMapper.takedownPost(report.getTargetId()) > 0
                        ? "帖子#" + report.getTargetId()
                        : "帖子(已下架或已删)";
            }
            case ReportTargetType.COMMENT -> { // 软删 + 置下架态
                yield reportMapper.takedownComment(report.getTargetId()) > 0
                        ? "评论#" + report.getTargetId()
                        : "评论(已下架或已删)";
            }
            case ReportTargetType.CHAT_MESSAGE -> { // 软删隐藏(私聊内容只在双方会话里)
                yield reportMapper.takedownChatMessage(report.getTargetId()) > 0
                        ? "消息#" + report.getTargetId()
                        : "消息(已下架或已删)";
            }
            case ReportTargetType.GROUP_MESSAGE -> { // 软删隐藏(群消息列表按 is_deleted 过滤)
                yield reportMapper.takedownGroupMessage(report.getTargetId()) > 0
                        ? "群消息#" + report.getTargetId()
                        : "群消息(已下架或已删)";
            }
            /*
             * 集市活动（targetType=6，2026-10-21 新增）。
             * 5 在契约里是「搭子（buddy_post）」——预留表已删除、无端点，
             * 处置会被 ReportTargetResolver 拦下；给集市单独一个编号，
             * 才是让「封禁作者 + 下架活动」这条链路真正可用。
             * 用 soft_delete 而不是 is_enable：后者表示「发布者主动暂停」，
             * 混用会让发布者以为自己误点了暂停、进而重新开启它。
             */
            case ReportTargetType.MKT_ACTIVITY -> {
                yield reportMapper.takedownMktActivity(report.getTargetId()) > 0
                        ? "集市活动#" + report.getTargetId()
                        : "集市活动(已下架或已删)";
            }
            /*
             * 文件（targetType=7，2026-10-27）。
             * 只置 status=3 下架，不物理删：文件对象与审核记录要留作申诉证据；
             * 删除对象由 FilePurgeJob 在 7 天后处理。
             */
            case ReportTargetType.FILE -> {
                yield reportMapper.takedownFile(report.getTargetId()) > 0
                        ? "文件#" + report.getTargetId()
                        : "文件(已下架或已删)";
            }
            /*
             * 圈子（targetType=9，2026-10-29）。
             * 「仅下架」对圈子就是解散：status=1 后列表/详情/搜索全过滤，
             * 圈内帖子级联 status=2，与圈主解散同一口径。不封圈主——
             * 圈子违规不等于圈主个人该被封。
             */
            case ReportTargetType.CIRCLE -> {
                if (reportMapper.takedownCircle(report.getTargetId()) > 0) {
                    reportMapper.takedownCirclePosts(report.getTargetId());
                    yield "圈子#" + report.getTargetId();
                }
                yield "圈子(已解散或已删)";
            }
            default -> null; // 1用户 / 5搭子(未上线):无内容可下架
        };
    }
}