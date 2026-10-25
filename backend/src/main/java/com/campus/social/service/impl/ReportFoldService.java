package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.social.common.ReportTargetType;
import com.campus.social.entity.Post;
import com.campus.social.entity.ReviewTask;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 举报阈值自动折叠（2026-10-24 新增，众包发现机制，替代 AI 预审的「发现」职能）。
 *
 * <p><b>机制</b>：同一帖子的<b>独立待处置举报人</b>数达到阈值（默认 3）时，
 * 立即把帖子折叠（status 1→0，出 Feed 出详情）并进人审队列。发现违规的
 * 时效从「管理员下次巡检」提前到「第 3 个举报人点下去的那一秒」。
 *
 * <p><b>为什么只做帖子</b>：帖子的折叠-恢复链路是现成的——status=0 进
 * review_task，管理员在审核台 approve 即恢复（复用 ReviewServiceImpl 的
 * CAS + 写扩散），reject 即维持下架。评论/集市/私信没有恢复通道
 * （takedown 后无 undo），贸然折叠会在管理员驳回举报时留下「内容回不来」
 * 的坑；等它们有恢复端点后再接入本机制。
 *
 * <p><b>两队列各司其职</b>：折叠产生的 review_task 是「内容通道」
 * （决定这条帖子最终可见与否）；原始举报仍在举报队列是「处罚通道」
 * （决定要不要封人）。管理员两边都会看到，处理顺序随意、互不阻塞。
 *
 * <p><b>计数口径</b>：COUNT(DISTINCT reporter_id) 且 status=0 且
 * handler_id IS NULL——同一人重复举报被 uk_report 天然去重；
 * 管理员处置（驳回/忽略）后该举报不再计入，恶意集中举报刷阈值,
 * 处置一轮即清零，想再触发需要新的独立举报人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportFoldService {

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final PostNotifySupport notifySupport;
    private final AuditService auditService;

    /** 独立举报人阈值；<=0 视为关闭本机制 */
    @Value("${campus.review.report-fold-threshold:3}")
    private int threshold;

    /** 内容待审(作者) 通知类型,与 PostWriteService 同值 */
    private static final int NOTIFY_TYPE_REVIEWING = 6;

    /**
     * 举报提交后调用（与举报同事务）。非帖子目标直接返回。
     *
     * <p>同事务的理由：计数包含本条未提交的举报（同连接可见），
     * 回滚时折叠与通知一并回滚，不会出现「举报没提交成功、帖子却被折叠」。
     */
    public void maybeFoldPost(int targetType, long targetId) {
        if (targetType != ReportTargetType.POST || threshold <= 0) {
            return;
        }
        int reporters = reportMapper.countPendingReporters(targetType, targetId);
        if (reporters < threshold) {
            return;
        }

        /*
         * CAS 折叠:仅 status=1(可见)才折。影响行=0 说明已被折过/已下架/已删,
         * 直接返回——幂等,不会重复建任务或重复通知作者。
         */
        int affected = postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, targetId)
                .eq(Post::getStatus, 1)
                .set(Post::getStatus, 0)
                .set(Post::getUpdateTime, java.time.LocalDateTime.now()));
        if (affected == 0) {
            return;
        }

        enqueueFoldTask(targetId, reporters);

        // 通知作者:内容因多人举报进入人工复核(与常规送审同一通知形态,不吓人)
        Long authorId = reportMapper.selectPostAuthorId(targetId);
        if (authorId != null) {
            notifySupport.pushNotify(authorId, NOTIFY_TYPE_REVIEWING, targetId, authorId, targetId);
        }

        auditService.recordSystem("report_fold", "post", targetId,
                "独立举报人数达到 " + reporters + "(阈值 " + threshold + "),自动折叠待人工复核");
        log.info("post auto-folded by reports: post={}, reporters={}, threshold={}",
                targetId, reporters, threshold);
    }

    /**
     * 建/复用 review_task。与 PostWriteService.enqueueReview 的编辑分支同口径:
     * 已有任务则重置为待审(编辑后又触发折叠的场景),无则插入。
     */
    private void enqueueFoldTask(long postId, int reporters) {
        List<ReviewTask> existing = reviewTaskMapper.selectList(
                new LambdaQueryWrapper<ReviewTask>().eq(ReviewTask::getPostId, postId));
        String reason = "多人举报(" + reporters + " 人)自动折叠,待人工复核";
        if (existing.isEmpty()) {
            ReviewTask task = new ReviewTask();
            task.setPostId(postId);
            task.setStatus(0);
            task.setReason(reason);
            reviewTaskMapper.insert(task);
            return;
        }
        ReviewTask t = existing.get(0);
        t.setStatus(0);
        t.setReviewerId(null);
        t.setReason(reason);
        reviewTaskMapper.updateById(t);
    }
}
