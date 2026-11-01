package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 报名乐观锁:
     * 同事务内:先 INSERT activity_signup(幂等键),再调此方法。
     * WHERE (capacity = 0 不限额 OR current_count < capacity) AND status IN (0,1,2)
     * 影响行 = 1 成功;影响行 = 0 回滚 signup 并返"活动已满/已结束"
     * status 流转:有限额且 current_count+1 == capacity 时翻为 1(已满);capacity=0 永不翻
     */
    @Update("UPDATE activity " +
            "SET current_count = current_count + 1, " +
            "    status = IF(capacity > 0 AND current_count + 1 >= capacity, 1, status), " +
            "    update_time = NOW() " +
            "WHERE id = #{activityId} AND status IN (0,1,2) " +
            "AND (capacity = 0 OR current_count < capacity) AND is_deleted = 0")
    int trySignin(@Param("activityId") Long activityId);

    /** 取消报名:current_count - 1,且有限额、status 1(已满)且退到 capacity 之下时翻回 0 */
    @Update("UPDATE activity " +
            "SET current_count = GREATEST(0, current_count - 1), " +
            "    status = IF(capacity > 0 AND status = 1 AND GREATEST(0, current_count - 1) < capacity, 0, status), " +
            "    update_time = NOW() " +
            "WHERE id = #{activityId} AND current_count > 0 AND is_deleted = 0")
    int cancelSignin(@Param("activityId") Long activityId);

    /** 注销级联:取消该用户创建且仍未结束的活动(0报名中/1已满/2进行中) */
    @Update("UPDATE activity SET status = 4, update_time = NOW() "
            + "WHERE id = #{activityId} AND status IN (0,1,2) AND is_deleted = 0")
    int cancelOpenById(@Param("activityId") Long activityId);

    /** 定时任务:到 start_time 0/1 → 2 */
    @Update("UPDATE activity SET status = 2, update_time = NOW() " +
            "WHERE status IN (0,1) AND start_time <= NOW() AND is_deleted = 0")
    int markInProgress();

    /** 定时任务:到 end_time 2 → 3 */
    @Update("UPDATE activity SET status = 3, update_time = NOW() " +
            "WHERE status = 2 AND end_time <= NOW() AND is_deleted = 0")
    int markFinished();

    /**
     * 每日对账:按 activity_signup 实际行数重算 current_count。
     * 只对 0/1 状态按容量纠正满员标记；2/3/4 是生命周期状态，不覆盖。
     */
    @Update("UPDATE activity a SET "
            + "current_count = (SELECT COUNT(*) FROM activity_signup s "
            + "  WHERE s.activity_id = a.id AND s.is_deleted = 0), "
            + "status = CASE "
            + "  WHEN a.status IN (0,1) AND a.capacity > 0 AND (SELECT COUNT(*) FROM activity_signup s2 "
            + "    WHERE s2.activity_id = a.id AND s2.is_deleted = 0) >= a.capacity THEN 1 "
            + "  WHEN a.status IN (0,1) THEN 0 "
            + "  ELSE a.status END, "
            + "update_time = NOW() "
            + "WHERE a.is_deleted = 0")
    int reconcileCurrentCount();

    /** 对账前统计报名数漂移的活动数 */
    @Select("SELECT COUNT(*) FROM activity a WHERE a.is_deleted = 0 "
            + "AND a.current_count <> (SELECT COUNT(*) FROM activity_signup s "
            + "WHERE s.activity_id = a.id AND s.is_deleted = 0)")
    int countCurrentCountDrift();
}
