package com.campus.social.service.support;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.impl.ReviewTrustService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 行为信任门槛（2026-10-30）。
 *
 * <p>校园信息是用户自行填写的资料，平台没有学籍库，不能把它当身份证明。
 * 高风险动作统一改看两件可验证的事：账号注册了多久、信誉分多少。
 * 这里集中判定，避免私信、建圈、建群、集市各写一套阈值。
 */
@Component
@RequiredArgsConstructor
public class CampusAccessService {

    private final UserMapper userMapper;
    private final ReviewTrustService trustService;
    private final CampusProperties campus;

    /**
     * 高风险动作的统一门槛。
     *
     * @return 当前信誉分（调用方若还要查配额，可直接复用，避免重复计算）
     */
    public int requireHighRisk(long userId, int minAgeHours, String action) {
        User user = userMapper.selectById(userId);
        return requireHighRisk(user, minAgeHours, action);
    }

    /** 已加载 User 的版本，供私信这类已经查过用户的链路复用 */
    public int requireHighRisk(User user, int minAgeHours, String action) {
        Decision d = evaluate(user, minAgeHours, action);
        if (!d.allowed()) {
            throw new BusinessException(d.code(), d.message());
        }
        return d.score();
    }

    /**
     * 陌生人搭话门槛的只读探测，不抛异常。
     *
     * <p>会话页要在「发送前」告诉用户这条会话能不能发；只靠发送后报错，
     * 页面会先显示「今天还剩 1 条」再被打回，前后矛盾。
     *
     * @return 未达标原因；null 表示 24 小时 + 信誉分门槛已满足
     */
    public String strangerChatBlockReason(User user) {
        Decision d = evaluate(user, campus.getAccessStrangerChatMinHours(), "发起搭话");
        return d.allowed() ? null : d.message();
    }

    private Decision evaluate(User user, int minAgeHours, String action) {
        if (user == null) {
            return new Decision(ResultCode.NOT_FOUND.getCode(), "用户不存在", 0);
        }
        if (Integer.valueOf(1).equals(user.getStatus())) {
            return new Decision(ResultCode.USER_FROZEN.getCode(), "账号已被冻结，无法" + action, 0);
        }
        if (Integer.valueOf(2).equals(user.getStatus())) {
            return new Decision(ResultCode.USER_CANCELLED.getCode(), "账号已注销，无法" + action, 0);
        }

        long ageHours = ageHours(user);
        if (ageHours < minAgeHours) {
            return new Decision(ResultCode.ACCOUNT_TOO_NEW.getCode(),
                    "账号注册满 " + durationText(minAgeHours) + "后才能" + action
                            + "，当前已注册 " + durationText(ageHours), 0);
        }

        int score = trustService.creditScore(user.getId());
        int minScore = campus.getAccessMinCredit();
        if (score < minScore) {
            return new Decision(ResultCode.CREDIT_TOO_LOW.getCode(),
                    "信誉分达到 " + minScore + " 分后才能" + action + "，当前 " + score + " 分", score);
        }
        return new Decision(0, null, score);
    }

    private record Decision(int code, String message, int score) {
        boolean allowed() {
            return code == 0;
        }
    }

    public int requireStrangerChat(long userId, String action) {
        return requireHighRisk(userId, campus.getAccessStrangerChatMinHours(), action);
    }

    public int requireStrangerChat(User user, String action) {
        return requireHighRisk(user, campus.getAccessStrangerChatMinHours(), action);
    }

    public int requireCreateCircle(long userId) {
        return requireHighRisk(userId, campus.getAccessCreateCircleMinDays() * 24, "创建圈子");
    }

    public int requireCreateMkt(long userId) {
        return requireHighRisk(userId, campus.getAccessCreateMktMinDays() * 24, "发布集市活动");
    }

    public int requireCreateGroup(long userId, String action) {
        return requireHighRisk(userId, campus.getAccessCreateGroupMinDays() * 24, action);
    }

    public int requireActivitySignup(long userId) {
        return requireHighRisk(userId, campus.getAccessActivitySignupMinDays() * 24, "报名活动");
    }

    private long ageHours(User user) {
        LocalDateTime created = user.getCreateTime();
        if (created == null) {
            return 0;
        }
        long hours = Duration.between(created, LocalDateTime.now()).toHours();
        return Math.max(0, hours);
    }

    private String durationText(long hours) {
        if (hours > 0 && hours % 24 == 0) {
            return (hours / 24) + " 天";
        }
        return Math.max(0, hours) + " 小时";
    }
}
