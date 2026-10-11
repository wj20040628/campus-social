package com.campus.social.service.support;

import com.campus.social.config.CampusProperties;
import com.campus.social.entity.SchoolDept;
import com.campus.social.entity.User;
import com.campus.social.service.DictService;
import com.campus.social.util.StudentNoParser;
import com.campus.social.vo.StudentNoParsedVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 学号「自动通过」判定（F2 认证提速）。
 *
 * <p><b>为什么抽成独立组件</b>：提交学号有两条入口——
 * <ul>
 *   <li>{@code POST /user/verify/student-no}（引导第 1 步 / 专门的认证提交）
 *   <li>{@code PUT /user/me} 改学号（设置页，经 StudentNoChangeSupport）
 * </ul>
 * 判定逻辑若写在其中一处，另一条路径的用户就会得到不同的待遇：
 * 引导页秒过、设置页却在排队等人工。两条路径必须共用同一把尺子。
 *
 * <p><b>判定 = 格式已过（调用方保证）+ 院系交叉校验</b>。
 * 学号里的专业大类（如 0809 计算机类）要与本人自填的院系对得上，
 * 对不上或大类未收录则一律转人工——宁可让少数人排队，
 * 也不能凭没核实的规则给陌生人盖章：认证后就能发动态、搭话、发公告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentNoAutoPassSupport {

    /**
     * 学号专业大类编码（位 3-6）→ 该大类可能归属的招生学院。
     *
     * <p>来源：黄河交通学院招生网专业设置 × 教育部本科专业目录（取大类前 4 位）。
     * 一个大类可能横跨多个学院（如 0802 机械类同时覆盖智能制造与智能车辆），
     * 所以值是列表而不是单值——写死单值会把另一个学院的真实学生误判成造假。
     *
     * <p>未收录的大类一律转人工，不猜、不默认放行。新增专业/学院时在这里补一行。
     */
    private static final Map<String, List<String>> MAJOR_CLASS_TO_DEPTS = Map.ofEntries(
            Map.entry("0809", List.of("人工智能学院")),                                    // 计算机类
            Map.entry("0802", List.of("智能制造与电气工程学院", "智能车辆与新能源工程学院")), // 机械类
            Map.entry("0818", List.of("智慧交通与智能建造工程学院", "智能车辆与新能源工程学院")), // 交通运输类
            Map.entry("0810", List.of("智慧交通与智能建造工程学院")),                       // 土木类
            Map.entry("0812", List.of("智慧交通与智能建造工程学院")),                       // 测绘类
            Map.entry("0805", List.of("智能车辆与新能源工程学院")),                        // 能源动力类
            Map.entry("0806", List.of("智能制造与电气工程学院")),                          // 电气类
            Map.entry("0807", List.of("智能制造与电气工程学院")),                          // 电子信息类
            Map.entry("1201", List.of("数字经济与智慧管理学院")),                          // 管理科学与工程
            Map.entry("1202", List.of("数字经济与智慧管理学院")),                          // 工商管理
            Map.entry("1206", List.of("数字经济与智慧管理学院")),                          // 物流管理
            Map.entry("1208", List.of("数字经济与智慧管理学院")),                          // 电子商务
            Map.entry("0203", List.of("数字经济与智慧管理学院")),                          // 金融学类
            Map.entry("0305", List.of("马克思主义学院")),                                  // 马克思主义理论
            Map.entry("1305", List.of("艺术与设计学院"))                                   // 设计学类
    );

    private final DictService dictService;
    private final CampusProperties campus;

    /** 自动通过开关（运营可关，关掉即退回全人工） */
    public boolean enabled() {
        return campus.isAutoApproveStudentNo();
    }

    /**
     * 能否自动通过。
     *
     * @param studentNo 已通过格式校验的学号
     * @param me        当前用户（用其自填院系做交叉校验）
     * @return true 可直接置为已认证
     */
    public boolean canAutoPass(String studentNo, User me) {
        if (!enabled()) {
            return false;
        }
        StudentNoParsedVO parsed = StudentNoParser.parse(studentNo);
        if (!parsed.isParsed()) {
            return false;
        }
        List<String> allowed = MAJOR_CLASS_TO_DEPTS.get(parsed.getMajorClassCode());
        if (allowed == null) {
            return false; // 大类未收录:交给人工,不猜
        }
        SchoolDept dept = dictService.findDeptForDisplay(me.getDeptId());
        if (dept == null || dept.getName() == null) {
            return false;
        }
        boolean ok = allowed.contains(dept.getName());
        if (!ok) {
            log.info("student no needs manual review: user={}, majorClass={}, dept={}",
                    me.getId(), parsed.getMajorClassCode(), dept.getName());
        }
        return ok;
    }
}
