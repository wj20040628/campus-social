package com.campus.social.service.impl;

import com.campus.social.config.CampusProperties;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.OnboardingCompleteDTO;
import com.campus.social.entity.Circle;
import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.SchoolMajor;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.mapper.UserTagMapper;
import com.campus.social.service.DictService;
import com.campus.social.service.OnboardingService;
import com.campus.social.service.TagService;
import com.campus.social.service.CircleService;
import com.campus.social.vo.OnboardingCompleteVO;
import com.campus.social.vo.OnboardingTagsVO;
import com.campus.social.vo.RecommendCircleListVO;
import com.campus.social.vo.RecommendCircleRow;
import com.campus.social.vo.RecommendCircleVO;
import com.campus.social.vo.RecommendUserListVO;
import com.campus.social.vo.RecommendUserRow;
import com.campus.social.vo.RecommendUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * F11 引导实现。
 *
 * <p>推荐一律走<b>确定性规则</b>而非算法（PRD §9 明确排除 AI 智能匹配）：
 * 推荐结果可解释、可复现、可在测试里断言。冷启动期没有行为数据，
 * 上算法只会得到「看起来随机」的推荐，反而降低信任。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingServiceImpl implements OnboardingService {

    /** 引导完成态。判据见 ARCHITECTURE §3 Q10.1 */
    private static final int STEP_DONE = 3;

    /** 打分位掩码：用于把 score 反解成「命中了哪几条规则」，从而生成可展示的理由 */
    private static final int BIT_DEPT = 4;
    private static final int BIT_GRADE = 2;
    private static final int BIT_VERIFIED = 1;

    /** verify_status = 1 */
    private static final int VERIFY_OK = 1;

    private final UserMapper userMapper;
    private final UserTagMapper userTagMapper;
    private final CircleMapper circleMapper;
    private final TagService tagService;
    private final DictService dictService;
    private final OnboardingFollowSupport followSupport;
    private final CircleService circleService;
    private final CampusProperties campus;

    @Override
    public OnboardingTagsVO listTags(int size) {
        return OnboardingTagsVO.builder()
                .tags(tagService.onboardingCandidates(size))
                .minSelect(campus.getOnboardingMinTags())
                .maxSelect(campus.getOnboardingMaxTags())
                .build();
    }

    @Override
    @Transactional
    public OnboardingCompleteVO complete(long userId, OnboardingCompleteDTO dto) {
        requireUser(userId);

        // 早前这里有 isDone(me) 快速短路(ARCHITECTURE §3 Q10.2):onboarding_step=3
        // 直接返回 duplicate。但前端是「每一步都调 complete」(§7,防中途中断丢数据):
        // 第 1 步写完 identity 就把 step 推到 3,第 2 步的 tags、第 3 步的 follows
        // 会被短路吞掉,永远存不上。幂等性改由「全量覆盖写」保证——
        // 相同数据重复提交结果不变,重复点「完成」无害。

        Long schoolId = dto.getSchoolId() == null ? campus.getSchoolId() : dto.getSchoolId();
        requireSchool(schoolId);

        // 字典校验必须在写库之前：引导完成是「一次提交做完三件事」，
        // 若先写 user 再发现院系 id 非法，虽然事务会回滚，但白跑一趟且
        // 错误语义变成 500 之外的模糊失败。放在最前面语义最清晰。
        SchoolDept dept = dictService.requireSelectableDept(dto.getDeptId(), schoolId);
        SchoolGrade grade = dictService.requireSelectableGrade(dto.getGradeId());
        // 专业必须属于所选学院：与 saveSchoolProfile 同口径，防止学院与专业错配。
        SchoolMajor major = dictService.requireSelectableMajor(dto.getMajorId(), dept.getId());

        // 幂等第 2 道防线：把 onboarding_step 推到 3，靠行锁串行化并发提交。
        // affected=0 说明已被并发请求抢先完成，后面的写库必须全部跳过。
        int affected = userMapper.completeOnboarding(
                userId, schoolId, dept.getId(), dept.getName(), grade.getId(), grade.getName(),
                major.getId());
        if (affected == 0) {
            log.info("onboarding complete raced: user={}", userId);
            return duplicateResult(userId);
        }

        // 空 tags = 本步未涉及标签(前端第 1 步还没有标签):跳过校验且不覆盖已有标签。
        // 非空才走「3-8 个」的业务校验并全量替换。
        List<Long> tagIds = dto.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            tagIds = validateTagCount(tagIds);
            tagService.replaceTagsInTx(userId, tagIds);
        } else {
            tagIds = List.of();
        }
        int followed = followSupport.followAll(userId, dto.getFollowUserIds());

        // 引导第 2 步勾选的圈子:在此批量加入(幂等,重复提交无害)。
        // 与关注同理放进 complete 这个「每步都调」的入口,避免中途中断丢数据。
        int joined = joinCircles(userId, dto.getCircleIds());

        log.info("onboarding completed: user={}, tags={}, followed={}, joined={}", userId, tagIds.size(), followed, joined);
        return OnboardingCompleteVO.builder()
                .completed(true)
                .duplicated(false)
                .onboardingStep(STEP_DONE)
                .followedCount(followed)
                .tagCount(tagIds.size())
                .build();
    }

    /**
     * 引导批量加圈。逐圈加,单圈失败(圈子不存在/已封禁等)只跳过该圈、不阻断整体完成。
     * 加圈逻辑复用 {@link CircleService#join}(其内含幂等判断与 member_count 自增)。
     */
    private int joinCircles(long userId, List<Long> circleIds) {
        if (circleIds == null || circleIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long cid : circleIds) {
            try {
                circleService.join(cid, userId);
                n++;
            } catch (BusinessException e) {
                log.warn("onboarding join circle skipped: user={}, circle={}, {}", userId, cid, e.getMessage());
            }
        }
        return n;
    }

    @Override
    public RecommendUserListVO recommendUsers(long userId, int size) {
        User me = requireUser(userId);
        // 同时传 id 与名称：id 走匹配通道，名称供 SQL 内部对dept_id 为 NULL 的
        // 老数据做回退比较（见 UserMapper#selectRecommendUsers 的注释）
        List<RecommendUserRow> rows = userMapper.selectRecommendUsers(
                userId, me.getDeptId(), me.getDept(), me.getGradeId(), me.getGrade(),
                campus.getSchoolId(), size);

        List<RecommendUserVO> users = new ArrayList<>(rows.size());
        for (RecommendUserRow row : rows) {
            users.add(RecommendUserVO.builder()
                    .id(row.getId())
                    .nickname(row.getNickname())
                    .avatarUrl(row.getAvatarUrl())
                    .dept(row.getDept())
                    .grade(row.getGrade())
                    .verified(Integer.valueOf(VERIFY_OK).equals(row.getVerifyStatus()))
                    .followerCount(row.getFollowerCount())
                    .reason(buildReason(row, me))
                    .commonTagCount(row.getCommonTagCount())
                    // SQL 已排除我关注过的人，这里恒 false；字段保留以兼容推荐规则放宽后的场景
                    .followed(false)
                    .build());
        }
        return RecommendUserListVO.builder()
                .users(users)
                .emptyHint(users.isEmpty() ? emptyUserHint(me) : null)
                .build();
    }

    @Override
    public RecommendCircleListVO recommendCircles(long userId, int size) {
        requireUser(userId);
        List<RecommendCircleRow> rows = circleMapper.selectRecommendCircles(userId, size);

        List<RecommendCircleVO> circles = new ArrayList<>(rows.size());
        for (RecommendCircleRow row : rows) {
            circles.add(RecommendCircleVO.builder()
                    .id(row.getId())
                    .name(row.getName())
                    .description(null)
                    .avatarUrl(null)
                    .memberCount(row.getMemberCount())
                    .commonTagCount(0)
                    .joined(false)
                    .build());
        }
        return RecommendCircleListVO.builder()
                .circles(circles)
                .emptyHint(circles.isEmpty() ? "圈子还在建，你可以先关注几个同学" : null)
                .build();
    }

    // ---------------------------------------------------------------- 私有

    private User requireUser(long userId) {
        User me = userMapper.selectById(userId);
        if (me == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(1).equals(me.getStatus())) {
            throw new BusinessException(ResultCode.USER_FROZEN, "账号已冻结");
        }
        return me;
    }

    /** 重复提交的回执：tagCount 回当前实际值而非 0，否则前端会以为标签被清空了 */
    private OnboardingCompleteVO duplicateResult(long userId) {
        int tagCount = userTagMapper.selectTagIdsByUser(userId).size();
        return OnboardingCompleteVO.builder()
                .completed(true)
                .duplicated(true)
                .onboardingStep(STEP_DONE)
                .followedCount(0)
                .tagCount(tagCount)
                .build();
    }

    private void requireSchool(Long schoolId) {
        if (schoolId == null || schoolId != campus.getSchoolId()) {
            // 与 saveSchoolProfile 同口径：放行外校 id 会让用户挂到不存在的学校，
            // 而推荐与公告全按 school_id 过滤，结果是他既收不到公告也推荐不出人
            throw new BusinessException(
                    ResultCode.UNPROCESSABLE, "当前仅支持本校，暂时无法切换学校");
        }
    }

    /**
     * 标签数量校验。
     *
     * <p>DTO 的 {@code @Size(min=3,max=8)} 管形状（违规 400），
     * 这里管业务值（回 422）：配置把 min 调到 5 时，
     * 只改 campus.onboarding-min-tags 就生效，不必动 DTO。
     */
    private List<Long> validateTagCount(List<Long> tagIds) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(tagIds));
        if (distinct.size() < campus.getOnboardingMinTags()) {
            throw new BusinessException(ResultCode.UNPROCESSABLE,
                    "请至少选择 " + campus.getOnboardingMinTags() + " 个兴趣标签");
        }
        if (distinct.size() > campus.getMaxTags()) {
            throw new BusinessException(ResultCode.UNPROCESSABLE,
                    "兴趣标签最多选择 " + campus.getMaxTags() + " 个");
        }
        return tagService.validateTagIds(distinct);
    }

    /**
     * 生成推荐理由（规则可解释是不上算法换来的最大好处）。
     *
     * <p>按打分位反解命中了哪几条规则，取权重最高的一条作为主因，
     * 再补上共同标签数——「同院系 · 你们都喜欢 3 个标签」比只说「同院系」更有说服力。
     */
    private String buildReason(RecommendUserRow row, User me) {
        int score = row.getScore() == null ? 0 : row.getScore();
        StringBuilder sb = new StringBuilder();
        if ((score & BIT_DEPT) == BIT_DEPT) {
            sb.append("同院系 · ").append(row.getDept());
        } else if ((score & BIT_GRADE) == BIT_GRADE) {
            sb.append("同年级 · ").append(row.getGrade());
        } else if ((score & BIT_VERIFIED) == BIT_VERIFIED) {
            sb.append("已通过校内认证");
        } else {
            sb.append("本校活跃同学");
        }
        int common = row.getCommonTagCount() == null ? 0 : row.getCommonTagCount();
        if (common > 0) {
            sb.append("，你们都喜欢 ").append(common).append(" 个标签");
        }
        return sb.toString();
    }

    /**
     * 空列表引导文案（AC-19）。
     *
     * <p>区分两种空：自己还没填归属（那是「先去填」而非「这里没人」），
     * 与填了但确实没匹配（那是「先看看全校活跃的人」）。
     * 两种情况给同一句话会让用户以为自己填错了。
     *
     * <p>判据复用 {@code DictService#hasDept/hasGrade}，与认证接口的 AC-18 判定同源：
     * 若这里自己写一套，老用户会在认证页被告知「已填」却在这里被告知「没填」。
     */
    private String emptyUserHint(User me) {
        boolean profileIncomplete = !dictService.hasDept(me) || !dictService.hasGrade(me);
        return profileIncomplete
                ? "先补全院系与年级，推荐出来的同学会更准"
                : "还没有同院系的同学注册，先看看全校活跃的人";
    }
}
