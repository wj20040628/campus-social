package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.SchoolMajor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SchoolMajorMapper extends BaseMapper<SchoolMajor> {

    /**
     * 级联下拉：按学院 + 层次取启用中的专业，按 sort 升序、id 升序兜底。
     *
     * <p>本专科(level)来自注册「本专科」下拉，与 dept_id 共同构成过滤维度，
     * 命中 school_major 索引 idx_dept_level_enabled_sort。
     */
    @Select("SELECT * FROM school_major "
            + "WHERE dept_id = #{deptId} AND level = #{level} "
            + "AND enabled = 1 AND is_deleted = 0 "
            + "ORDER BY sort ASC, id ASC")
    List<SchoolMajor> selectEnabledByDeptAndLevel(@Param("deptId") Long deptId,
                                                  @Param("level") Integer level);

    /**
     * 校验用户提交的专业 id 是否可被选择。
     *
     * <p>跨学院保护：专业挂在学院下，选了 A 学院却提交 B 学院的专业 id 必须拒绝，
     * 否则学号专业码与学院对不上的脏数据会写进 user 表。
     */
    @Select("SELECT * FROM school_major "
            + "WHERE id = #{id} AND dept_id = #{deptId} "
            + "AND enabled = 1 AND is_deleted = 0")
    SchoolMajor selectSelectable(@Param("id") Long id, @Param("deptId") Long deptId);

    /** 回显用：按 id 取专业，不过滤 enabled。取不到返回 null 由调用方决定降级 */
    @Select("SELECT * FROM school_major WHERE id = #{id} AND is_deleted = 0")
    SchoolMajor selectByIdIgnoreEnabled(@Param("id") Long id);

    /**
     * 一次拉全校专业(挂载预取用):启用中、未删除,按学院/层次/排序稳定排序。
     * 专业下拉改为前端按「学院 + 本专科」过滤,本查询替代逐组合的级联查询。
     */
    @Select("SELECT * FROM school_major "
            + "WHERE school_id = #{schoolId} AND enabled = 1 AND is_deleted = 0 "
            + "ORDER BY dept_id ASC, level ASC, sort ASC, id ASC")
    List<SchoolMajor> selectAllEnabledBySchool(@Param("schoolId") Long schoolId);
}
