package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.UserMuteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户级禁言实现（2026-10-31）。
 *
 * <p>只写 user.mute_until，不用 Redis 计划表：禁言到期靠时间比较自动失效，
 * 没有「到期后还要恢复某个状态」的动作，不需要 BanExpireJob 那套 ZSET。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMuteServiceImpl implements UserMuteService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserMapper userMapper;

    @Override
    public LocalDateTime mute(long userId, int days) {
        LocalDateTime until = LocalDateTime.now().plusDays(days);
        // 只延长不缩短：已有一个更晚的禁言期限时，再次处置不能把期限改早，
        // 否则「30 天禁言后再点一次 1 天」会变成提前解禁。
        LocalDateTime current = userMapper.selectMuteUntil(userId);
        if (current != null && current.isAfter(until)) {
            until = current;
        }
        userMapper.updateMuteUntil(userId, until);
        userMapper.touchLastPunish(userId);
        log.info("user muted until {}: user={}, days={}", until, userId, days);
        return until;
    }

    @Override
    public void unmute(long userId) {
        userMapper.updateMuteUntil(userId, null);
        log.info("user unmuted: user={}", userId);
    }

    @Override
    public LocalDateTime mutedUntil(long userId) {
        return userMapper.selectMuteUntil(userId);
    }

    @Override
    public void requireCanSpeak(long userId, String action) {
        requireCanSpeak(userMapper.selectMuteUntil(userId), action);
    }

    @Override
    public void requireCanSpeak(User user, String action) {
        if (user != null) {
            requireCanSpeak(user.getMuteUntil(), action);
        }
    }

    private void requireCanSpeak(LocalDateTime until, String action) {
        if (until != null && until.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.USER_MUTED,
                    "账号已被禁言，将于 " + until.format(FMT) + " 自动解除，期间无法" + action);
        }
    }
}
