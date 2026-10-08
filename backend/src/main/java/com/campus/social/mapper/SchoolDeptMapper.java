package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.SchoolDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SchoolDeptMapper extends BaseMapper<SchoolDept> {

    /**
     * 引导页下拉：只取启用项，按 sort 升序、id 升序兜底。
     *
     * <p>id ASC 兜底不是为了好看：sort 由运营手工维护，重复值必然存在，
     * 而 ORDER BY 不稳定会让每次翻页返回顺序不同的列表。
     */
    @Select("SELECT * FROM school_dept "
            + "WHERE school_id = #{schoolId} AND enabled = 1 AND is_deleted = 0 "
            + "ORDER BY sort ASC, id ASC")
    List<SchoolDept> selectEnabledBySchool(@Param("schoolId") Long schoolId);

    /**
     * 校验用户提交的 deptId 是否可被选择。
     *
     * <p>只查 enabled=1，不查 is_deleted（MP 的逻辑删除对本@Select 不生效，需显式带上）。
     * 停用的院系必须拒绝——否则用户能选到学校已撤销的院系。
     */
    @Select("SELECT * FROM school_dept "
            + "WHERE id = #{id} AND school_id = #{schoolId} AND enabled = 1 AND is_deleted = 0")
    SchoolDept selectSelectable(@Param("id") Long id, @Param("schoolId") Long schoolId);

    /**
     * 按 id 取名称，不过滤 enabled。
     *
     * <p>用于回显历史用户的院系名：停用院系的老用户主页仍要显示原名，
     * 若按 enabled=1 过滤会渲染成空白，比显示一个已撤销的院系名更糟。
     */
    @Select("SELECT * FROM school_dept WHERE id = #{id} AND is_deleted = 0")
    SchoolDept selectByIdIgnoreEnabled(@Param("id") Long id);

    /**
     * 批量按 id 取名称，供列表页一次性解析，避免 N+1。
     *
     * <p>传空列表会在 SQL 里拼出 {@code IN ()} 语法错误，调用方必须先判空。
     */
    @Select("<script>"
            + "SELECT * FROM school_dept WHERE is_deleted = 0 AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<SchoolDept> selectByIds(@Param("ids") List<Long> ids);
}