package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.dto.CreateCommentDTO;
import com.campus.social.entity.Comment;
import com.campus.social.entity.Like;
import com.campus.social.entity.Post;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.CommentMapper;
import com.campus.social.mapper.LikeMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.support.CommentTreeAssembler;
import com.campus.social.util.SensitiveWordFilter;
import com.campus.social.vo.CommentChildrenPageVO;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.UserVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.campus.social.common.ResultCode.COMMENT_NOT_FOUND;
import static com.campus.social.common.ResultCode.POST_NOT_FOUND;

/**
 * 评论域实现（从 PostServiceImpl 拆出）。
 *
 * <p><b>为什么拆</b>：评论有自己独立的三级层级规则、@提及解析、按层级分派通知目标、
 * 以及独立的「点赞对象是评论而非帖子」判定，与帖子的写扩散/Feed 游标逻辑没有共用代码。
 * 混在一个类里会让「一次评论请求会改动哪些表」这件事无法一眼看完。
 *
 * <p><b>事务边界保持原样</b>：评论落库 + 帖子评论数 +1 + 热度分 + 通知
 * 仍在同一事务内，拆类不改变原子性（Spring 事务基于代理，同类内部调用不生效，
 * 故这里由 PostServiceImpl 注入本类并跨类调用，事务照常生效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl {

    private static final int TARGET_TYPE_COMMENT = 2;
    private static final int NOTIFY_TYPE_COMMENT = 3;
    private static final int NOTIFY_TYPE_MENTION = 9;  // 评论中被 @

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final UserMapper userMapper;
    private final SensitiveWordFilter sensitive;
    private final ObjectMapper om;
    private final com.campus.social.websocket.WsPushService wsPush;
    /** 通知下发复用写路径的横切能力,保证「未读数 + WS 推送」口径一致 */
    private final PostNotifySupport notifySupport;
    /** 读侧树组装：作者/被回复人/点赞态全部批量取，避免逐条查 */
    private final CommentTreeAssembler treeAssembler;
    /** 圈内封禁拦截:评论前查 ban_status */
    private final com.campus.social.service.CircleAdminService circleAdminService;

    @Transactional
    public long comment(long postId, CreateCommentDTO dto, long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不可评论");
        }
        // 圈内封禁拦截:帖子属某圈且评论者被圈内封禁 → 拒(圈主/圈管理恒不拦,见 isBanned)
        if (post.getCircleId() != null && circleAdminService.isBanned(post.getCircleId(), userId)) {
            throw new BusinessException(com.campus.social.common.ResultCode.CIRCLE_MEMBER_BANNED,
                    "你已被圈主/圈管理封禁,无法在此圈子操作");
        }
        // 评论也过敏感词
        String content = dto.getContent();
        if (sensitive.containsBlock(content)) {
            throw new BusinessException(com.campus.social.common.ResultCode.SENSITIVE_WORD_BLOCKED);
        }
        content = sensitive.replaceAll(content);

        int level = dto.getLevel() == null ? 1 : dto.getLevel();
        if (level < 1 || level > 3) {
            throw new BusinessException(com.campus.social.common.ResultCode.BAD_REQUEST.getCode(), "评论层级 1~3");
        }
        /*
         * parentId 归属校验(2026-10-23 加,审查 P0-8)。
         *
         * 修复前的攻击面:parentId 原样落库、通知分支也不校验 parent.postId ——
         *   1) 提交 level=2 + parentId=<B 帖某评论 id> → 「被评论」通知精准投给 B 帖评论者
         *      (任意人,正文由攻击者控制),可批量定向骚扰;
         *   2) 评论行落在 A 帖但 parent_id 指向 B 帖 → 计入 A 帖 comment_count 却永不出现在
         *      A 帖列表(幽灵评论);
         *   3) level=1 + parentId 非空 这类矛盾组合会被树装配当成根重复挂载。
         *
         * 校验口径刻意**不强制 parentId 必填**:前端当前并不传 parentId(已核实零命中),
         * 强制必填等于直接关掉二/三级回复。只做「带了就必须合法」:
         *   - level=1 不允许带 parentId(矛盾);
         *   - level∈(2,3) 带 parentId 时,父评论必须存在、正常态、且属于**本帖**;
         *   - 层级必须严格相邻(2 的父是 1,3 的父是 2),防跳级挂载。
         */
        if (level == 1 && dto.getParentId() != null) {
            throw new BusinessException(com.campus.social.common.ResultCode.BAD_REQUEST.getCode(),
                    "一级评论不能指定父评论");
        }
        if (level > 1 && dto.getParentId() != null) {
            Comment parent = commentMapper.selectById(dto.getParentId());
            if (parent == null || !Integer.valueOf(1).equals(parent.getStatus())) {
                throw new BusinessException(com.campus.social.common.ResultCode.BAD_REQUEST.getCode(),
                        "父评论不存在");
            }
            if (!parent.getPostId().equals(postId)) {
                throw new BusinessException(com.campus.social.common.ResultCode.BAD_REQUEST.getCode(),
                        "父评论不属于本帖");
            }
            if (parent.getLevel() == null || parent.getLevel() != level - 1) {
                throw new BusinessException(com.campus.social.common.ResultCode.BAD_REQUEST.getCode(),
                        "父评论层级不匹配");
            }
        }

        Comment c = new Comment();
        c.setPostId(postId);
        c.setUserId(userId);
        c.setParentId(dto.getParentId());
        c.setLevel(level);
        c.setReplyToUserId(dto.getReplyToUserId());
        c.setContent(content);
        c.setStatus(1);
        bindMentioned(c, dto);
        commentMapper.insert(c);

        postMapper.adjustCommentCount(postId, 1);
        // 热度分
        // 增量自增(审查 P1-15):之前是「查出来 +2.0 再绝对值写回」,并发下会丢增量
        postMapper.adjustHotScore(postId, 2.0);

        pushCommentNotify(c, dto, post, userId);
        return c.getId();
    }

    /**
     * 评论列表：一级评论 + 各条的children 树。
     *
     * <p><b>分页只作用于一级评论</b>：子树不参与分页，
     * 否则同一条一级评论会在第 1 页出现、第 2 页又出现。
     *
     * <p><b>查询次数固定为 3次</b>：一级评论、子树评论、然后是assembler 里的
     * 用户与点赞批量取。不用「先取一级再逐条查子评论」是因为那样
     * 一页 20 条就是 41 次查询，且条数随数据波动。
     */
    public CursorPage<CommentVO> comments(long postId, Long lastId, int size, long viewerId) {
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getStatus, 1)
                .eq(Comment::getLevel, 1)
                .lt(lastId != null, Comment::getId, lastId)
                .orderByDesc(Comment::getId)
                .last("LIMIT " + (size + 1));
        List<Comment> rows = commentMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<Comment> page = rows.stream().limit(size).toList();

        // 子树只在有数据时才查：空列表省掉一次往返
        List<Comment> replies = page.isEmpty()
                ? List.of()
                : commentMapper.selectRepliesOfPost(postId);

        List<CommentVO> vos = treeAssembler.assemble(page, replies, viewerId);
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /**
     * 某条评论的全部直接子回复（分页）。
     *
     * <p><b>存在的理由是 children 有 20 条上限，不是为了「一次性拿全部评论」</b>。
     * 溢出时前端用本端点补齐，否则用户会以为回复被系统吞了。
     *
     * <p><b>只查直接子回复、不递归</b>：二级的孩子是三级，三级没有孩子，
     * 递归最多一层就该停。再往下前端拿本页返回的 id 再调一次即可。
     *
     * <p><b>游标方向与一级列表相反</b>：这里取 id 升序返回最新一页
     * （契约原文「取 id 升序、返回用 id 降序，故首页返回的是最新 20 条」）。
     * 原因是 children 溢出时被丢的是「最老的那批」，
     * 补齐时应当优先补最新的，而不是把用户已经看过的再给一遍。
     */
    public CommentChildrenPageVO commentChildren(long postId, long commentId,
            Long lastId, int size, long viewerId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(POST_NOT_FOUND, "帖子不存在");
        }
        Comment parent = commentMapper.selectById(commentId);
        if (parent == null || !Integer.valueOf(1).equals(parent.getStatus())
                || parent.getPostId() != postId) {
            throw new BusinessException(COMMENT_NOT_FOUND, "评论不存在");
        }

        int total = commentMapper.countChildren(commentId);
        // id 升序取(size + 1) 条：多出的那一条就是 hasMore 的判据
        List<Comment> children = commentMapper.selectChildrenForPage(commentId, lastId, size + 1);
        boolean hasMore = children.size() > size;
        List<Comment> page = children.stream().limit(size).toList();

        List<CommentVO> list = treeAssembler.assembleAsLeaves(page, viewerId);
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return CommentChildrenPageVO.builder()
                .list(list)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .totalForParent(total)
                .build();
    }

    /**
     * 解析并写入被 @ 用户映射。
     *
     * <p>存「昵称→id」而非只存 id：昵称可能重名，前端要据此把 @昵称 渲染成可点跳转，
     * 只存 id 就得再发一次请求反查昵称。
     */
    private void bindMentioned(Comment c, CreateCommentDTO dto) {
        if (dto.getMentionUserIds() == null || dto.getMentionUserIds().isEmpty()) {
            return;
        }
        Map<String, Long> mentioned = new LinkedHashMap<>();
        for (Long mid : dto.getMentionUserIds().stream().distinct().toList()) {
            User mu = userMapper.selectById(mid);
            if (mu != null && mu.getStatus() != 2) {
                // 昵称为空时回退到 username,但**必须脱敏**(审查 P1-22):
                // username 就是学号,原样返回等于把完整学号暴露给任意访客
                mentioned.put(mu.getNickname() != null ? mu.getNickname()
                        : com.campus.social.assembler.UserVoAssembler.maskUsername(mu.getUsername()), mu.getId());
            }
        }
        if (mentioned.isEmpty()) {
            return;
        }
        try {
            c.setMentioned(om.writeValueAsString(mentioned));
        } catch (Exception ignored) {
            // 提及映射序列化失败不阻塞评论本身:评论已能正常展示,
            // 只是 @ 昵称不可点击。丢一个可点的 @ 比丢整条评论划算
        }
    }

    /**
     * 通知发送：按评论层级分派目标 + 单独给被 @ 者发 type=9。
     *
     * <p>归属人头（被通知方）取决于层级：一级评论通知帖子作者，
     * 二级通知一级评论者，三级通知被回复的人。发错人会让用户收到与自己无关的红点。
     */
    private void pushCommentNotify(Comment c, CreateCommentDTO dto, Post post, long userId) {
        Long notifyTarget;
        int level = c.getLevel();
        if (level == 1) {
            notifyTarget = post.getAuthorId();
        } else if (level == 2 && dto.getParentId() != null) {
            Comment parent = commentMapper.selectById(dto.getParentId());
            // 双保险(审查 P0-8):即便写入侧校验被绕过,通知目标也只认「本帖的父评论」,
            // 否则攻击者可用别帖的评论 id 把通知定向投给任意人
            notifyTarget = (parent != null && parent.getPostId().equals(post.getId()))
                    ? parent.getUserId() : post.getAuthorId();
        } else {
            notifyTarget = resolveLevel3Target(dto, post);
        }
        if (!notifyTarget.equals(userId)) {
            notifySupport.pushNotify(notifyTarget, NOTIFY_TYPE_COMMENT, c.getId(), userId, c.getPostId());
        }

        // 被 @ 通知(type=9):按前端传来的唯一 id 精准发,昵称不唯一所以不靠正文反查。
        // 排除自己、去重、跳过已失效用户
        if (dto.getMentionUserIds() == null || dto.getMentionUserIds().isEmpty()) {
            return;
        }
        List<Long> mentionIds = dto.getMentionUserIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(mid -> !mid.equals(userId))
                .toList();
        for (Long mid : mentionIds) {
            User mentioned = userMapper.selectById(mid);
            if (mentioned != null && mentioned.getStatus() != 2) {
                notifySupport.pushNotify(mid, NOTIFY_TYPE_MENTION, c.getId(), userId, c.getPostId());
            }
        }
    }

    /**
     * 三级评论的通知接收方。
     *
     * <p><b>为什么校验「是否参与过本帖」而不是「是否等于 parentId 的作者」</b>：
     * 三级评论走的是 else 分支，本方法所在的 if 链里只有 {@code level == 2} 才会去查
     * {@code parentId}，所以在这里反查 parentId 时它可能为 null、也可能指向一级评论，
     * <b>取不到「本次回复对象」的可靠依据</b>。而「B 是不是 C 想回复的人」这个意图
     * 只有客户端知道，服务端无论怎么查都是猜测。
     *
     * <p><b>为什么不能用「更严格 = 必须匹配 parentId 作者」</b>：那会把正常的
     * 「A 评论 → B 回复 A → C 回复 B」误判成非法。更糟的是前端当前
     * <b>完全没有传 parentId</b>（已grep 确认零命中），严格口径下会把所有三级回复
     * 全部误判，等于直接关掉这个功能。
     *
     * <p><b>本方法挡得住</b>：伪造他人 userId 定向骚扰、把A 帖的 id 传到 B 帖（跨帖骚扰）、
     * 前端 bug 导致的误传。
     *
     * <p><b>本方法挡不住（已知局限，不视为缺陷）</b>：该用户确实参与过本帖讨论，
     * 但发评论者想通知的不是他。服务端无从区分，因为「回复意图」不在数据里。
     *
     * <p><b>为什么降级给帖子作者而不是丢弃</b>：丢弃会让「我回复了人却没人收到」
     * 表现得像系统坏了；降级至少让通知有一个可解释的落点。通知错人是骚扰，
     * 静默吞掉同样不是好结果，两者之间取「可解释」这一侧。
     */
    private Long resolveLevel3Target(CreateCommentDTO dto, Post post) {
        Long target = dto.getReplyToUserId();
        if (target == null) {
            return post.getAuthorId();
        }
        boolean participated = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, post.getId())
                .eq(Comment::getUserId, target)
                .eq(Comment::getStatus, 1)) > 0;
        return participated ? target : post.getAuthorId();
    }


    /** 解析 comment.mentioned JSON 为 昵称→id 映射;解析失败返回 null(不阻塞评论展示) */
    private Map<String, Long> parseMentioned(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json, new TypeReference<Map<String, Long>>() { });
        } catch (Exception e) {
            return null;
        }
    }
}
