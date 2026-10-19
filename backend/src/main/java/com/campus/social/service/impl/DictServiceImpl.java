package com.campus.social.service.impl;

import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.SchoolMajor;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.SchoolDeptMapper;
import com.campus.social.mapper.SchoolGradeMapper;
import com.campus.social.mapper.SchoolMajorMapper;
import com.campus.social.service.DictService;
import com.campus.social.vo.DeptItemVO;
import com.campus.social.vo.GradeItemVO;
import com.campus.social.vo.MajorItemVO;
import com.campus.social.vo.SchoolDictVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.campus.social.common.ResultCode.DEPT_INVALID;
import static com.campus.social.common.ResultCode.GRADE_INVALID;
import static com.campus.social.common.ResultCode.MAJOR_INVALID;

/**
 * 字典实现：本地无状态 + Redis 缓存（TTL 10min）。
 *
 * <p><b>为什么用 Redis 而不是本地 Caffeine</b>：契约写的是「本地 Caffeine 或 Redis，
 * 二选一」。选 Redis 的理由是本项目已有一个多实例部署形态（docker-compose 里
 * 应用与 worker 同镜像），本地缓存在多实例下会出现「实例 A 已加载新字典、实例 B 仍是旧的」
 * 的不一致，而字典变更虽低频、影响却是全站展示。选 Redis 后各实例口径一致。
 * Caffeine 需要新增依赖，本轮不引入。
 *
 * <p><b>Redis 5.0 兼容</b>：本文件只用了 GET 与 SETEX（{@code opsForValue().set(k,v,ttl)}），
 * 两者都是 Redis 2.6 时代就有的命令，不在禁用清单内。
 * 缓存里没有原子读删需求，因此不涉及 {@code RedisLua}。
 *
 * <p><b>缓存降级</b>：Redis 不可用时直接回落查库，不抛错。
 * 字典是引导第 1 步的必经数据源，让它因为缓存故障而整个不可用，
 * 是把「加速手段」变成了「单点故障」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictServiceImpl implements DictService {

    /** 缓存键前缀。与 code: / campus: 等既有前缀同风格，便于运维按前缀排查 */
    private static final String KEY_SCHOOL_DICT = "dict:school:";

    /** 全校专业缓存键前缀(挂载预取,前端按学院+层次过滤) */
    private static final String KEY_MAJORS_ALL = "dict:majors:";

    /** 契约要求 TTL 10min（openapi.yaml §5.2 对字典缓存的约定） */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final SchoolDeptMapper deptMapper;
    private final SchoolGradeMapper gradeMapper;
    private final SchoolMajorMapper majorMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper om;

    /**
     * 一次返回院系与年级。
     *
     * <p>合并的原因见 {@code SchoolDictVO}：引导第 1 步分两次拉会出现
     * 「学院已选、年级还在转圈」的中间态。
     */
    @Override
    public SchoolDictVO listSchoolDict(Long schoolId) {
        String key = KEY_SCHOOL_DICT + schoolId;
        SchoolDictVO cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        List<DeptItemVO> depts = new ArrayList<>();
        for (SchoolDept d : deptMapper.selectEnabledBySchool(schoolId)) {
            depts.add(DeptItemVO.builder().id(d.getId()).name(d.getName()).sort(d.getSort()).build());
        }
        List<GradeItemVO> grades = new ArrayList<>();
        for (SchoolGrade g : gradeMapper.selectEnabled()) {
            grades.add(GradeItemVO.builder()
                    .id(g.getId())
                    .name(g.getName())
                    .enrollYear(g.getEnrollYear())
                    .sort(g.getSort())
                    .build());
        }
        SchoolDictVO vo = SchoolDictVO.builder()
                .schoolId(schoolId)
                .depts(depts)
                .grades(grades)
                .deptTotal(depts.size())
                .gradeTotal(grades.size())
                .build();
        writeCache(key, vo);
        return vo;
    }

    /**
     * 校验 deptId。<b>刻意读库不走缓存</b>。
     *
     * <p>字典列表允许 10 分钟的陈旧（顶多让用户看到刚被撤销的院系），
     * 但「能不能选」是写路径的准入判断：读缓存会让一个刚被停用的院系
     * 在 10 分钟内仍可被写入 user 表。单行主键查询成本极低，
     * 用它换「写路径绝不基于陈旧数据」是划算的。
     */
    @Override
    public SchoolDept requireSelectableDept(Long deptId, Long schoolId) {
        if (deptId == null) {
            throw new BusinessException(DEPT_INVALID, "请选择学院");
        }
        SchoolDept dept = deptMapper.selectSelectable(deptId, schoolId);
        if (dept == null) {
            throw new BusinessException(DEPT_INVALID, "所选学院不存在或已停用，请重新选择");
        }
        return dept;
    }

    @Override
    public SchoolGrade requireSelectableGrade(Long gradeId) {
        if (gradeId == null) {
            throw new BusinessException(GRADE_INVALID, "请选择年级");
        }
        SchoolGrade grade = gradeMapper.selectSelectable(gradeId);
        if (grade == null) {
            throw new BusinessException(GRADE_INVALID, "所选年级不存在或已停用，请重新选择");
        }
        return grade;
    }

    @Override
    public SchoolDept findDeptForDisplay(Long deptId) {
        return deptId == null ? null : deptMapper.selectByIdIgnoreEnabled(deptId);
    }

    @Override
    public SchoolGrade findGradeForDisplay(Long gradeId) {
        return gradeId == null ? null : gradeMapper.selectByIdIgnoreEnabled(gradeId);
    }

    /**
     * 学院 → 专业级联下拉。
     *
     * <p>按「学院 + 本专科」过滤，命中 school_major 索引 idx_dept_level_enabled_sort。
     * 频次低（用户每次改下拉才查一次）、结果集极小（一个学院一个层次通常十来条），
     * 不进 Redis 缓存：缓存键随 dept+level 组合膨胀，收益抵不过一致性复杂度。
     * 直接查库，故障面更小。
     */
    @Override
    public List<MajorItemVO> listMajors(Long deptId, Integer level) {
        List<MajorItemVO> majors = new ArrayList<>();
        if (deptId == null || level == null) {
            return majors;
        }
        for (SchoolMajor m : majorMapper.selectEnabledByDeptAndLevel(deptId, level)) {
            majors.add(MajorItemVO.builder()
                    .id(m.getId())
                    .name(m.getName())
                    .code(m.getCode())
                    .level(m.getLevel())
                    .sort(m.getSort())
                    .build());
        }
        return majors;
    }

    @Override
    public SchoolMajor requireSelectableMajor(Long majorId, Long deptId) {
        if (majorId == null) {
            throw new BusinessException(MAJOR_INVALID, "请选择专业");
        }
        SchoolMajor major = majorMapper.selectSelectable(majorId, deptId);
        if (major == null) {
            throw new BusinessException(MAJOR_INVALID, "所选专业不存在或已停用，请重新选择");
        }
        return major;
    }

    /**
     * 一次返回本校全部专业(挂载预取用)。
     *
     * <p>专业下拉改为前端按「学院 + 本专科」即时过滤,不再逐组合发请求,
     * 因此把全校专业一次性下发,带 Redis 缓存(TTL 10min,与 listSchoolDict 同口径)。
     * 单校专业量极小(几十行),一次返回成本可忽略,却彻底消除了前端级联的
     * 竞态与「首次选完学院+本专科却没加载出来」的交互问题。
     */
    @Override
    public List<MajorItemVO> listAllMajors(Long schoolId) {
        String key = KEY_MAJORS_ALL + schoolId;
        List<MajorItemVO> cached = readCacheMajors(key);
        if (cached != null) {
            return cached;
        }
        List<MajorItemVO> majors = new ArrayList<>();
        for (SchoolMajor m : majorMapper.selectAllEnabledBySchool(schoolId)) {
            majors.add(MajorItemVO.builder()
                    .id(m.getId())
                    .deptId(m.getDeptId())
                    .name(m.getName())
                    .code(m.getCode())
                    .level(m.getLevel())
                    .sort(m.getSort())
                    .build());
        }
        writeCacheMajors(key, majors);
        return majors;
    }

    /**
     * 院系是否已填：字典 id 或历史名称任一存在即算已填。
     *
     * <p>兼容期是刻意保留的：字典化上线前注册的用户只有 dept 字符串、没有 dept_id。
     * 若一律只认 id，这批用户会在认证与引导两步同时被卡住，提示语却是「请填写」——
     * 他们明明填过，这是最令人困惑的一类错误。等存量数据全部回填 dept_id 后可删掉这层回退。
     */
    @Override
    public boolean hasDept(User user) {
        return user != null && (user.getDeptId() != null || notBlank(user.getDept()));
    }

    @Override
    public boolean hasGrade(User user) {
        return user != null && (user.getGradeId() != null || notBlank(user.getGrade()));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---------------------------------------------------------------- 缓存读写

    /**
     * 读缓存。任何异常都当作「没命中」处理，降级查库，不把缓存故障暴露给用户。
     *
     * <p>返回 null 表示未命中（含「当前无启用项」的情形，此时会去查库一次再把空结果缓存住，
     * 避免每次请求都打库）。
     */
    private SchoolDictVO readCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return om.readValue(json, SchoolDictVO.class);
        } catch (Exception e) {
            log.warn("dict cache read failed, fallback to db. key={}, cause={}", key, e.toString());
            return null;
        }
    }

    private void writeCache(String key, SchoolDictVO vo) {
        try {
            redis.opsForValue().set(key, om.writeValueAsString(vo), TTL);
        } catch (Exception e) {
            log.warn("dict cache write failed, ignore. key={}, cause={}", key, e.toString());
        }
    }

    private List<MajorItemVO> readCacheMajors(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return om.readValue(json, new TypeReference<List<MajorItemVO>>() {});
        } catch (Exception e) {
            log.warn("dict majors cache read failed, fallback to db. key={}, cause={}", key, e.toString());
            return null;
        }
    }

    private void writeCacheMajors(String key, List<MajorItemVO> list) {
        try {
            redis.opsForValue().set(key, om.writeValueAsString(list), TTL);
        } catch (Exception e) {
            log.warn("dict majors cache write failed, ignore. key={}, cause={}", key, e.toString());
        }
    }
}