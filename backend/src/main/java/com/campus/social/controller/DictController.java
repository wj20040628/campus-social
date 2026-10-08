package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.config.CampusProperties;
import com.campus.social.service.DictService;
import com.campus.social.vo.SchoolDictVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 院系与年级字典端点（支撑 F11 引导第 1 步）。
 *
 * <p>与 {@code TagController} 分开成独立控制器而不是塞进去：
 * 兴趣标签归 F11 第 2 步、院系年级归第 1 步，两者的缓存键与变更频率都不同，
 * 混在一个类里会让「改哪个缓存」的判断变难。
 *
 * <p>字典只读，不提供增删改：院系由学校人工维护，开放写接口等于允许
 * 任意用户给自己造一个院系，脏字典会直接毁掉 AC-14 的匹配价值。
 */
@RestController
@RequestMapping("/dict")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;
    private final CampusProperties campus;

    /**
     * 一次返回院系与年级。
     *
     * <p>合并成一个端点而非两个：引导第 1 步的三个下拉要同时可用，
     * 分两次请求会出现「学院已选、年级还在转圈」的中间态。
     *
     * <p>{@code schoolId} 不传时取配置默认值（恒为 1）。
     * 传了别的值也照常按该值过滤院系而不是报错：本端点是纯字典查询，
     * 没有「写入归属」那层越权风险，与 school-profile 的强校验口径不同。
     * 注意年级不受 schoolId 影响——年级跨校通用。
     */
    @GetMapping("/school")
    public Result<SchoolDictVO> schoolDict(@RequestParam(value = "schoolId", required = false) Long schoolId) {
        Long sid = schoolId == null ? campus.getSchoolId() : schoolId;
        return Result.ok(dictService.listSchoolDict(sid));
    }
}