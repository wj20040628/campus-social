-- 上传文件归属表(OSS 接入 P1~P4 新增;本地库此前漏建,此处补建)。
-- 作用:
--   1. 头像/群头像归属校验(isAvatarAllowed):引用 /uploads/* 的文件必须确属当前用户(群主)上传,
--      否则拒,堵住「任意登录用户引用他人上传 URL 当自己头像」的越权。
--   2. OSS 前端直传回调落库(OssCallbackController):文件已落 OSS 后,回调据此记 owner + 幂等。
-- 兼容:老数据 / 外部图床 URL 没有归属记录,校验方对「查无记录」放行(不断老数据)。
-- 命名:MyBatis-Plus 默认驼峰转下划线(userId->user_id 等),与实体 UploadFile 一一对应。
CREATE TABLE IF NOT EXISTS upload_file (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NULL     COMMENT '上传者 id(user.id);群头像归属到建群/改头像成员',
    url         VARCHAR(512) NOT NULL COMMENT '相对 URL,如 /uploads/20261013/uuid.mp4',
    kind        VARCHAR(16)  NULL     COMMENT 'image | video',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted  INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常,1 已删(MyBatis-Plus @TableLogic)',
    PRIMARY KEY (id),
    KEY idx_url (url),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传文件归属表';
