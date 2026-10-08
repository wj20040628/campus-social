package com.campus.social.service;

import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.SchoolGrade;
import com.campus.social.entity.User;
import com.campus.social.vo.SchoolDictVO;

/**
 * 院系与年级字典（F11 引导第 1 步的数据源）。
 *
 * <p>只读，不提供增删改：字典由后台人工维护，开放写接口等于把
 * 「任意用户给自己造一个院系」变成可能，脏字典会直接毁掉 AC-14 的匹配价值。
 */
public interface DictService {

    /** 本校启用中的院系 + 启用中的年级（带 Redis 缓存，TTL 10min） */
    SchoolDictVO listSchoolDict(Long schoolId);

    /**
     * 校验并解析 deptId 为院系实体。
     *
     * @throws com.campus.social.exception.BusinessException id 不存在、属于别的学校或已停用时抛 1040
     */
    SchoolDept requireSelectableDept(Long deptId, Long schoolId);

    /** 同上，错误码 1041 */
    SchoolGrade requireSelectableGrade(Long gradeId);

    /** 回显用：按 id 取院系，不过滤 enabled。取不到返回 null 由调用方决定降级 */
    SchoolDept findDeptForDisplay(Long deptId);

    /** 回显用：按 id 取年级，不过滤 enabled */
    SchoolGrade findGradeForDisplay(Long gradeId);

    /**
     * 用户的院系是否算「已填」（AC-18 判定）。
     *
     * <p>放在这里而不是各自实现，是因为字典化前后两套判据必须完全一致：
     * 引导、认证、推荐空态文案三处都要用，少一处就会让用户在不同页面
     * 被告知互相矛盾的「你还没填」提示。
     */
    boolean hasDept(User user);

    /** 同上，判年级。AC-18 要求 dept 与 grade 同时已填 */
    boolean hasGrade(User user);
}