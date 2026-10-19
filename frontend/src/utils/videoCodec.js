/**
 * 前端视频编码预检:在上传前读文件头判断是否为 H.265/HEVC,
 * 浏览器普遍播不了,提前拦下比传完才失败友好(与后端 VideoCodecUtil 同判据)。
 *
 * 扫全文件字节找 HEVC 样本条目 fourcc:hvc1 / hev1 / dvh1 / hvcC。
 * 用 latin1 解码成字符串再 includes 扫描(对 ASCII fourcc 安全),25MB 内可接受。
 *
 * @param {File} file
 * @returns {Promise<boolean>} true=疑似 HEVC(应拒)
 */
export async function isHevcFile(file) {
  try {
    const buf = await file.arrayBuffer()
    const view = new Uint8Array(buf)
    const hay = new TextDecoder('latin1').decode(view)
    return (
      hay.includes('hvc1') ||
      hay.includes('hev1') ||
      hay.includes('dvh1') ||
      hay.includes('hvcC')
    )
  } catch {
    return false
  }
}

export default isHevcFile

/**
 * 发视频入口展示给用户的格式说明。
 * 必须与后端 {@code UploadServiceImpl.VIDEO_EXT}(mp4/webm/mov/mkv)
 * 和 HEVC 拦截(hvc1/hev1/dvh1/hvcC)保持一致 —— 后端改白名单时这里同步改。
 */
export const VIDEO_SUPPORTED_FORMATS = 'mp4 / webm / mov / mkv（H.264 编码）'
export const VIDEO_UNSUPPORTED_NOTE = '不支持 H.265/HEVC'
