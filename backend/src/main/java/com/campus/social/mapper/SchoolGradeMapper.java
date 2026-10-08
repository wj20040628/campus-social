package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.SchoolGrade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SchoolGradeMapper extends BaseMapper<SchoolGrade> {

    /** 引导页下拉：只取启用项。sort ASC 让最近入学的年级排前面 */
    @Select("SELECT * FROM school_grade "
            + "WHERE enabled = 1 AND is_deleted = 0 "
            + "ORDER BY sort ASC, id ASC")
    List<SchoolGrade> selectEnabled();

    /** 校验提交的 gradeId 可被选择。已毕业年级（enabled=0）必须拒绝 */
    @Select("SELECT * FROM school_grade "
            + "WHERE id = #{id} AND enabled = 1 AND is_deleted = 0")
    SchoolGrade selectSelectable(@Param("id") Long id);

    /** 回显用，不过滤 enabled：老用户仍要看到自己毕业那年的年级 */
    @Select("SELECT * FROM school_grade WHERE id = #{id} AND is_deleted = 0")
    SchoolGrade selectByIdIgnoreEnabled(@Param("id") Long id);

    /** 批量取名称供列表页一次解析，避免 N+1；空列表调用方必须先判空 */
    @Select("<script>"
            + "SELECT * FROM school_grade WHERE is_deleted = 0 AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<SchoolGrade> selectByIds(@Param("ids") List<Long> ids);
}