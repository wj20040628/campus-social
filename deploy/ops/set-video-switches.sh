#!/usr/bin/env bash
# 把两个视频降本开关写进 /opt/campus/.env 的注释区（幂等）。
# 只追加注释，不改现有配置、不重启服务；需要降级时手动去掉行首 # 再重启。
# 用法：sudo bash set-video-switches.sh
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/campus/.env}"
MARKER="# --- 视频降本开关（set-video-switches.sh）---"

if [ ! -f "$ENV_FILE" ]; then
  echo "错误：找不到 $ENV_FILE" >&2
  exit 1
fi

if grep -qE 'UPLOAD_VIDEO_ENABLED|UPLOAD_MAX_VIDEO_BYTES|视频降本开关' "$ENV_FILE"; then
  echo "已存在视频开关注释或配置，跳过追加。现有行："
  grep -nE 'UPLOAD_VIDEO_ENABLED|UPLOAD_MAX_VIDEO_BYTES|视频降本开关' "$ENV_FILE"
  exit 0
fi

BACKUP="${ENV_FILE}.bak.$(date +%Y%m%d_%H%M%S)"
cp -p "$ENV_FILE" "$BACKUP"
chmod 600 "$BACKUP" 2>/dev/null || true

cat >> "$ENV_FILE" <<'EOF'

# --- 视频降本开关（set-video-switches.sh）---
# 需要降级时，去掉对应行首的 # 并重启 campus；平时保持注释，不影响现有行为。
# 视频上传总开关：true=正常收视频；false=前端隐藏视频入口、后端拒收视频。
# UPLOAD_VIDEO_ENABLED=false
# 单条视频字节上限：10485760=10MB；降本时可改 5242880=5MB。
# UPLOAD_MAX_VIDEO_BYTES=5242880
EOF

echo "已写入：$ENV_FILE"
echo "备份：$BACKUP"
grep -nE '视频降本开关|UPLOAD_VIDEO_ENABLED|UPLOAD_MAX_VIDEO_BYTES' "$ENV_FILE"