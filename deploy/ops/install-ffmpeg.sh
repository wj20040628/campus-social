#!/usr/bin/env bash
# 安装随部署包携带的 ffmpeg 静态二进制到 /usr/local/bin(视频上传 720p 压缩依赖)。
# 在 ECS 宝塔终端以 root 执行;幂等:已有带 libx264 的 ffmpeg 则跳过。
# 用法: bash install-ffmpeg.sh [二进制路径](默认取脚本同目录的 ffmpeg-linux-x86_64-v7.0.2)
set -euo pipefail
SRC="${1:-$(cd "$(dirname "$0")" && pwd)/ffmpeg-linux-x86_64-v7.0.2}"
DEST=/usr/local/bin/ffmpeg
MD5=d5c698e0e98e5cf7de03fe14bd1cda6a

[ -s "$SRC" ] || { echo "缺 ffmpeg 二进制: $SRC" >&2; exit 1; }
echo "$MD5  $SRC" | md5sum -c - || { echo "二进制校验失败,勿安装" >&2; exit 1; }

if [ -x "$DEST" ] && "$DEST" -hide_banner -encoders 2>/dev/null | grep -q libx264; then
  echo "已有可用 ffmpeg(含 libx264): $("$DEST" -version | head -1),跳过安装"
  exit 0
fi
install -m 0755 "$SRC" "$DEST"
"$DEST" -version | head -1
"$DEST" -hide_banner -encoders 2>/dev/null | grep -q libx264 || { echo "安装后仍缺 libx264" >&2; exit 1; }
echo "ffmpeg 安装完成: $DEST(后端默认从 PATH 查找,systemd 默认 PATH 含 /usr/local/bin)"