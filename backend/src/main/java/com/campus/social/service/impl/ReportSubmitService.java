package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ReportSubmitDTO;
import com.campus.social.entity.Report;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportSubmitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 举报提交（2026-10-30 从 ReportServiceImpl 拆出）。
 *
 * <p>幂等与并发两道防线：
 * <ul>
 *   <li>唯一索引 {@code uk_report(reporter_id,target_type,target_id)} 兜底，
 *       插入用<b>纯 INSERT</b>让唯一键抛 {@code DuplicateKeyException}，而不是
 *       {@code ON DUPLICATE KEY UPDATE}——后者把冲突吞掉且返回值不可靠
 *       （Connector/J 默认带 CLIENT_FOUND_ROWS，冲突赋回原值也返回 1），
 *       调用方的 {@code affected == 0} 分支就成了永不执行的死代码</li>
 *   <li>事务边界在 {@link ReportService} 门面上：折叠计数要能读到本条未提交的行</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSubmitService {

    private static final int STATUS_PENDING = 0;

    private final ReportMapper reportMapper;
    /** 举报阈值折叠(2026-10-24):同一帖子独立举报人达到阈值即折叠进人审 */
    private final ReportFoldService foldService;
    /** 举报集中自动封禁(2026-10-25):24h 内多名认证举报人 → 限期封禁止血 */
    private final ReportAutoBanService autoBanService;
    /** 举报时目标快照(2026-10-29):作者删帖后管理员仍能看到原文/作者/媒体 */
    private final ReportSnapshotService snapshotService;

    public ReportSubmitVO submit(long reporterId, ReportSubmitDTO dto) {
        // 先查一次：命中就是幂等重复，直接回显原记录。
        // 这不是并发保护（并发时两个请求可能都查不到），
        // 真正的兜底是唯一索引 uk_report 抛 DuplicateKeyException。
        var existing = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
        if (existing != null) {
            return duplicated(existing);
        }

        try {
            /*
             * 快照在插入举报前抓取：作者随后删帖/删评论，管理员仍能看到举报时的原文。
             * 抓取失败不影响举报主流程（captureQuietly 返回 null）。
             */
            String snapshot = snapshotService.captureQuietly(dto.getTargetType(), dto.getTargetId());
            reportMapper.insertReport(reporterId, dto.getTargetType(), dto.getTargetId(),
                    dto.getReasonType(), dto.getReasonText(), snapshot);
        } catch (DuplicateKeyException e) {
            // 并发窗口内另一个请求刚插进同一条唯一键。回查拿 id，不能当成失败报错——
            // 对用户来说「重复举报」不是错误。
            var again = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
            if (again == null) {
                // 撞了唯一键却查不到行，只可能是这行被别人硬删了（正常业务不该发生）。
                // 此时回 500 让上层感知，而不是凭空造一个 reportId 返回给用户。
                log.warn("report unique key hit but row vanished: reporter={}, type={}, target={}",
                        reporterId, dto.getTargetType(), dto.getTargetId());
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "举报提交异常，请重试");
            }
            return duplicated(again);
        }

        var created = reportMapper.selectByTarget(reporterId, dto.getTargetType(), dto.getTargetId());
        log.info("report submitted: reporter={}, type={}, target={}, reason={}",
                reporterId, dto.getTargetType(), dto.getTargetId(), dto.getReasonType());

        /*
         * 阈值折叠检查(2026-10-24):与举报同一事务——计数包含本条未提交的行,
         * 回滚时折叠/任务/通知一并回滚。幂等重复(duplicated)不触发:
         * 同一人重复举报既不增加独立举报人数,也不该反复进入折叠分支。
         */
        foldService.maybeFoldPost(dto.getTargetType(), dto.getTargetId());

        /*
         * 集中举报自动封禁检查(2026-10-25):注册到事务提交后执行——
         * 封禁含不可回滚的 Redis 写,举报若回滚不能留下"人被封了"的幽灵。
         * 幂等重复不触发,理由同上。
         */
        if (created != null) {
            autoBanService.scheduleCheckAfterCommit(created);
        }

        return ReportSubmitVO.builder()
                .reportId(created == null ? null : created.getId())
                .duplicated(false)
                .status(STATUS_PENDING)
                .build();
    }

    /** 幂等重复：回显既有记录，不新增行 */
    private ReportSubmitVO duplicated(Report report) {
        return ReportSubmitVO.builder()
                .reportId(report.getId())
                .duplicated(true)
                .status(report.getStatus())
                .build();
    }
}
