package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Announcement;
import com.campus.social.entity.Circle;
import com.campus.social.entity.Comment;
import com.campus.social.entity.Group;
import com.campus.social.entity.MktActivity;
import com.campus.social.entity.Post;
import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.mapper.AnnouncementMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CommentMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.ReviewTaskMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AdminOverviewService;
import com.campus.social.service.AdminUserService;
import com.campus.social.vo.AdminOverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理总览实现。口径说明：
 * <ul>
 *   <li>「在册用户」= status != 2（封禁中的仍是用户，算在册）；</li>
 *   <li>「帖子」只数 status = 1（公开可见），审核中/下架/删除都不算，
 *       与首页能刷到的数量对得上；</li>
 *   <li>「今日」一律按自然日 00:00（服务器时区）起算，不是滚动 24 小时；</li>
 *   <li>逻辑删除由实体上的 {@code @TableLogic} 自动带 is_deleted = 0，
 *       本类不再手写该条件，避免两套口径。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewServiceImpl implements AdminOverviewService {

    private final AdminUserService adminUserService;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final CircleMapper circleMapper;
    private final GroupMapper groupMapper;
    private final MktActivityMapper mktActivityMapper;
    private final AnnouncementMapper announcementMapper;
    private final ReportMapper reportMapper;
    private final ReviewTaskMapper reviewTaskMapper;

    @Override
    public AdminOverviewVO overview() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        AdminOverviewVO vo = new AdminOverviewVO();
        vo.setPending(adminUserService.pendingSummary());

        vo.setActiveUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>().ne(User::getStatus, 2)));
        vo.setCancelledUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStatus, 2)));
        vo.setBannedUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStatus, 1)));
        vo.setMutedUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 0)
                        .gt(User::getMuteUntil, now)));

        vo.setPosts(postMapper.selectCount(
                new LambdaQueryWrapper<Post>().eq(Post::getStatus, 1)));
        vo.setComments(commentMapper.selectCount(
                new LambdaQueryWrapper<Comment>().eq(Comment::getStatus, 1)));
        vo.setCircles(circleMapper.selectCount(
                new LambdaQueryWrapper<Circle>().eq(Circle::getStatus, 0)));
        vo.setGroups(groupMapper.selectCount(
                new LambdaQueryWrapper<Group>().eq(Group::getStatus, 1)));
        vo.setMktActive(mktActivityMapper.selectCount(
                new LambdaQueryWrapper<MktActivity>()
                        .eq(MktActivity::getIsEnable, 1)
                        .gt(MktActivity::getExpireTime, now)));
        vo.setAnnouncements(announcementMapper.selectCount(
                new LambdaQueryWrapper<Announcement>().eq(Announcement::getStatus, 1)));

        vo.setTodayNewUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .ge(User::getCreateTime, todayStart)
                        .ne(User::getStatus, 2)));
        vo.setTodayNewPosts(postMapper.selectCount(
                new LambdaQueryWrapper<Post>()
                        .ge(Post::getCreateTime, todayStart)
                        .ne(Post::getStatus, 3)));
        vo.setTodayReports(reportMapper.selectCount(
                new LambdaQueryWrapper<Report>().ge(Report::getCreateTime, todayStart)));

        // 昨日窗口 = [昨天 00:00, 今天 00:00)；与今日同口径，跨日不会重叠
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        vo.setYesterdayNewUsers(userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .ge(User::getCreateTime, yesterdayStart)
                        .lt(User::getCreateTime, todayStart)
                        .ne(User::getStatus, 2)));
        vo.setYesterdayNewPosts(postMapper.selectCount(
                new LambdaQueryWrapper<Post>()
                        .ge(Post::getCreateTime, yesterdayStart)
                        .lt(Post::getCreateTime, todayStart)
                        .ne(Post::getStatus, 3)));
        vo.setYesterdayReports(reportMapper.selectCount(
                new LambdaQueryWrapper<Report>()
                        .ge(Report::getCreateTime, yesterdayStart)
                        .lt(Report::getCreateTime, todayStart)));

        vo.setOldestPendingReviewHours(hoursSince(
                reviewTaskMapper.selectOldestPendingCreateTime(), now));
        vo.setOldestPendingReportHours(hoursSince(
                reportMapper.selectOldestPendingCreateTime(), now));
        return vo;
    }

    /** 积压时长取整到小时；未来时间/空值一律 0，避免总览出现负数 */
    private static long hoursSince(LocalDateTime since, LocalDateTime now) {
        return since == null ? 0 : Math.max(0, java.time.Duration.between(since, now).toHours());
    }
}
