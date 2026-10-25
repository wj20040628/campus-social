package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.mapper.UserTagMapper;
import com.campus.social.vo.RecommendUserListVO;
import com.campus.social.vo.RecommendUserRow;
import com.campus.social.vo.RecommendUserVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;

/**
 * 本周搭子匹配（2026-10-25）。
 *
 * <p>把已有的三块能力——兴趣标签（user_tag）、规则推荐（selectRecommendUsers 的
 * 打分范式）、免好友搭话（ChatGuard + stranger quota）——串成一个「主动撮合」服务：
 * 按共同标签 x4 + 同院系 2 + 同年级 1 打分，每周出一批人，用户点「想认识」
 * 走现成的搭话通道。PRD §9 明确排除 AI 匹配，这里与全项目一致用确定性规则。
 *
 * <p><b>「每周」的实现选型</b>：Redis 周键缓存（{@code buddy:week:{uid}:{ISO周}}，
 * TTL 8 天自然跨周过期），不建表、不加定时任务。与 StrangerQuotaServiceImpl
 * 的「按日期分桶、自然过期」同构。首次访问计算并写入，同周内所有人看到的
 * 都是同一批——「本周搭子」要是每次刷新都换人，用户会理解成随机推荐而不是每周撮合。
 *
 * <p>Redis 不可用时直接现算（fail-open）：推荐是增强体验，不能让缓存故障
 * 把整个发现页打挂。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuddyMatchService {

    /** 搭子推荐结果的打分位（与 selectWeeklyBuddies 的权重对齐,生成 reason 用） */
    private static final int BIT_DEPT = 2;
    private static final int BIT_GRADE = 1;

    private static final String KEY_PREFIX = "buddy:week:";
    private static final Duration WEEK_TTL = Duration.ofDays(8);

    private final UserMapper userMapper;
    private final UserTagMapper userTagMapper;
    private final CampusProperties campus;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    public RecommendUserListVO weeklyBuddies(long userId, int size) {
        User me = requireUser(userId);

        List<RecommendUserRow> rows = readWeekCache(userId);
        if (rows == null) {
            rows = userMapper.selectWeeklyBuddies(userId, me.getDeptId(), me.getDept(),
                    me.getGradeId(), me.getGrade(), campus.getSchoolId(), size);
            writeWeekCache(userId, rows);
        } else {
            // 缓存里存的是本周首批结果,请求 size 更大时截断即可(缓存按上限 size 写入)
            rows = rows.stream().limit(size).toList();
        }

        List<RecommendUserVO> users = rows.stream().map(row -> RecommendUserVO.builder()
                .id(row.getId())
                .nickname(row.getNickname())
                .avatarUrl(row.getAvatarUrl())
                .dept(row.getDept())
                .grade(row.getGrade())
                .verified(Integer.valueOf(1).equals(row.getVerifyStatus()))
                .followerCount(row.getFollowerCount())
                .reason(buildReason(row))
                .commonTagCount(row.getCommonTagCount())
                .followed(false)
                .build()).toList();

        return RecommendUserListVO.builder()
                .users(users)
                .emptyHint(users.isEmpty() ? emptyHint(me) : null)
                .build();
    }

    /** 同周同结果:键带 ISO 周号,自然跨周失效。读失败返回 null(现算兜底) */
    private List<RecommendUserRow> readWeekCache(long userId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + userId + ":" + weekKey());
            if (json == null || json.isBlank()) {
                return null;
            }
            return om.readValue(json, new TypeReference<List<RecommendUserRow>>() {
            });
        } catch (Exception e) {
            log.warn("buddy week cache read failed, fallback to live query: {}", e.getMessage());
            return null;
        }
    }

    private void writeWeekCache(long userId, List<RecommendUserRow> rows) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId + ":" + weekKey(),
                    om.writeValueAsString(rows), WEEK_TTL);
        } catch (Exception e) {
            log.warn("buddy week cache write failed (non-fatal): {}", e.getMessage());
        }
    }

    /** ISO-8601 周号,如 2026-W43。周一为一周之始,与「每周一出新搭子」的直觉一致 */
    private String weekKey() {
        WeekFields wf = WeekFields.ISO;
        LocalDate now = LocalDate.now();
        return now.get(wf.weekBasedYear()) + "-W" + now.get(wf.weekOfWeekBasedYear());
    }

    /** 理由:标签是主因(搭子的本质),院系/年级是加分项 */
    private String buildReason(RecommendUserRow row) {
        int common = row.getCommonTagCount() == null ? 0 : row.getCommonTagCount();
        StringBuilder sb = new StringBuilder("都喜欢 ").append(common).append(" 个标签");
        int score = row.getScore() == null ? 0 : row.getScore();
        if ((score & BIT_DEPT) == BIT_DEPT) {
            sb.append("，还同院系");
        } else if ((score & BIT_GRADE) == BIT_GRADE) {
            sb.append("，还同年级");
        }
        return sb.toString();
    }

    /**
     * 空态文案:与 OnboardingServiceImpl.emptyUserHint 同思路——
     * 「我没标签」是自己的问题(去选标签),「有标签但没人」才是供给问题。
     */
    private String emptyHint(User me) {
        boolean hasTags = !userTagMapper.selectTagIdsByUser(me.getId()).isEmpty();
        return hasTags
                ? "本周还没凑到同好，先去发现页看看同好的人"
                : "先在个人资料里选几个兴趣标签，下周给你配搭子";
    }

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
}
