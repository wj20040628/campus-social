package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.UploadFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UploadFileMapper extends BaseMapper<UploadFile> {

    /**
     * 查某 url 当前归属的用户 id;无记录返回 null(兼容老数据/外部图床)。
     * 同一 url 理论唯一(UUID 命名),is_deleted=0 兜住软删。
     */
    @Select("SELECT user_id FROM upload_file WHERE url = #{url} AND is_deleted = 0 LIMIT 1")
    Long ownerOf(@Param("url") String url);
}
