package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.dto.MktReactivDTO;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktCommentVO;

/**
 * 校园限时活动集市（mkt）服务。
 * 活动本身无审核流程；沟通只走站内公共留言区，不存任何对外联系方式。
 */
public interface MktActivityService {

    /** 创建一条集市活动，返回新 id。 */
    Long create(long userId, MktActivityDTO dto);

    /** 广场：投放中、未满员、未过期、开启；可一级分类过滤。游标分页。 */
    CursorPage<MktActivityVO> plaza(String category, Long lastId, int size);

    /** 我的发布：某用户未软删的活动，带服务端推导的 4 态 status。游标分页。 */
    CursorPage<MktActivityVO> myList(long userId, Long lastId, int size);

    /** 详情：软删 404；过期/暂停/满员仍可看。 */
    MktActivityVO detail(long id, long viewerId);

    /** 编辑：属主校验 + 敏感词；仅改文案字段，不改 expire_time。 */
    void edit(long id, long me, MktActivityDTO dto);

    /** 开/关（属主）。 */
    void toggleEnable(long id, long me, boolean enable);

    /** 手动满员标记（属主）。 */
    void markFull(long id, long me, boolean full);

    /** 重新激活：属主 + 12h 上限 + 24h/3 次限流。 */
    void reactivate(long id, long me, MktReactivDTO dto);

    /** 软删（属主）。 */
    void delete(long id, long me);

    /** 发留言：限流 + 敏感词；对发布者下发站内通知。 */
    void addComment(long activityId, long userId, MktCommentDTO dto);

    /** 留言列表：游标分页。 */
    CursorPage<MktCommentVO> listComments(long activityId, Long lastId, int size);
}
