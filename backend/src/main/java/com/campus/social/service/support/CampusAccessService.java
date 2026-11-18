package com.campus.social.service.support;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.impl.ReviewTrustService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 行为信任门槛（2026-11-17）。
 *
 * <p>注册即可用：私信搭话、建圈、建群与集市发布统一只校验信誉分
 * （基准 60，新注册账号即达标），不再要求账号注册满 24 小时。
 * 防滥用由配额、频率限流、内容审核与举报处置承担，不靠等待挡新用户。
 */
@Component
@RequiredArgsConstructor
public class CampusAccessService {

    private final UserMapper userMapper;
    private final ReviewTrustService trustService;
    private final CampusProperties campus;

    /**
     * 只校验信誉分（集市发布、私信搭话、建圈、建群共用）。
     *
     * @return 当前信誉分（调用方若还要查配额，可直接复用，避免重复计算）
     */
    public int requireCredit(long userId, String action) {
        return requireCredit(userMapper.selectById(userId), action);
    }

    /** 已加载 User 的版本，供私信这类已经查过用户的链路复用 */
    private int requireCredit(User user, String action) {
        Decision d = evaluate(user, action);
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
     * @return 未达标原因；null 表示信誉分门槛已满足
     */
    public String strangerChatBlockReason(User user) {
        Decision d = evaluate(user, "发起搭话");
        return d.allowed() ? null : d.message();
    }

    private Decision evaluate(User user, String action) {
        if (user == null) {
            return new Decision(ResultCode.NOT_FOUND.getCode(), "用户不存在", 0);
        }
        if (Integer.valueOf(1).equals(user.getStatus())) {
            return new Decision(ResultCode.USER_FROZEN.getCode(), "账号已被冻结，无法" + action, 0);
        }
        if (Integer.valueOf(2).equals(user.getStatus())) {
            return new Decision(ResultCode.USER_CANCELLED.getCode(), "账号已注销，无法" + action, 0);
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
        return requireCredit(userId, action);
    }

    public int requireStrangerChat(User user, String action) {
        return requireCredit(user, action);
    }

    public int requireCreateCircle(long userId) {
        return requireCredit(userId, "创建圈子");
    }

    public int requireCreateGroup(long userId, String action) {
        return requireCredit(userId, action);
    }
}
