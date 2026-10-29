package com.campus.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.social.entity.FileFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileFavoriteMapper extends BaseMapper<FileFavorite> {

    /** 物理删除：软删残留行会占着 uk_fav_user_file，再收藏必撞 Duplicate */
    @Delete("DELETE FROM file_favorite WHERE user_id = #{userId} AND file_id = #{fileId}")
    int hardDelete(@Param("userId") long userId, @Param("fileId") long fileId);

    /** 文件删除时清掉所有人的收藏，避免留下指向不存在文件的死行 */
    @Delete("DELETE FROM file_favorite WHERE file_id = #{fileId}")
    int hardDeleteByFile(@Param("fileId") long fileId);
}
