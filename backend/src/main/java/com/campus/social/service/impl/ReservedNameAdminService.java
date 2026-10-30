package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.ReservedNameDTO;
import com.campus.social.entity.ReservedCircleName;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ReservedCircleNameMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.ReservedNameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.CONFLICT;
import static com.campus.social.common.ResultCode.NOT_FOUND;

/**
 * 官方名额（预留圈名）运营后台（2026-10-30 从 CircleAdminServiceImpl 拆出）。
 * 增删查均需平台管理员。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservedNameAdminService {

    private final ReservedCircleNameMapper reservedNameMapper;
    private final RoleService roleService;
    private final AuditService auditService;

    public void addReservedName(ReservedNameDTO dto, long adminId) {
        roleService.requireAdmin(adminId);
        String name = dto.getName().trim();
        if (name.length() < 1 || name.length() > 64) {
            throw new BusinessException(BAD_REQUEST, "圈子名长度需在 1-64 字符");
        }
        Long cnt = reservedNameMapper.selectCount(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name));
        if (cnt != 0) {
            throw new BusinessException(CONFLICT, "该圈子名已在预留表");
        }
        ReservedCircleName rn = new ReservedCircleName();
        rn.setName(name);
        rn.setNote(dto.getNote());
        reservedNameMapper.insert(rn);
        auditService.record(adminId, "reserved_name_add", "reserved_circle_name", rn.getId(),
                "预留圈名 " + name, null);
        log.info("reserved name added: {} by admin={}", name, adminId);
    }

    public void removeReservedName(String name, long adminId) {
        roleService.requireAdmin(adminId);
        ReservedCircleName rn = reservedNameMapper.selectOne(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name));
        if (rn == null) {
            throw new BusinessException(NOT_FOUND, "预留名不存在");
        }
        reservedNameMapper.deleteById(rn.getId());
        auditService.record(adminId, "reserved_name_remove", "reserved_circle_name", rn.getId(),
                "移除预留圈名 " + name, null);
        log.info("reserved name removed: {} by admin={}", name, adminId);
    }

    public CursorPage<ReservedNameVO> listReservedNames(Long lastId, int size, long adminId) {
        roleService.requireAdmin(adminId);
        LambdaQueryWrapper<ReservedCircleName> qw = new LambdaQueryWrapper<ReservedCircleName>()
                .lt(lastId != null, ReservedCircleName::getId, lastId)
                .orderByDesc(ReservedCircleName::getId)
                .last("LIMIT " + (size + 1));
        List<ReservedCircleName> rows = reservedNameMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<ReservedNameVO> page = rows.stream().limit(size)
                .map(r -> ReservedNameVO.builder()
                        .id(r.getId()).name(r.getName()).note(r.getNote()).createTime(r.getCreateTime())
                        .build())
                .toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(page, nextCursor, hasMore);
    }

    public boolean isReservedName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Long cnt = reservedNameMapper.selectCount(new LambdaQueryWrapper<ReservedCircleName>()
                .eq(ReservedCircleName::getName, name.trim()));
        return cnt != 0;
    }
}
