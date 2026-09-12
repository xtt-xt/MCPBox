#!/usr/bin/env bash
# MCP 文件盒 · 发版脚本
#
# 版本号规则：v<发布版本>-<核心版本>，例如 v1.0.0-22
#   · 发布版本 1.0.0 —— 只在正式发布发行版时递增（按需求决定 1.1.0 / 2.0.0 …）
#   · 核心版本 22    —— 每次构建都 +1，等于 Android 的 versionCode
#
# 用法：
#   tools/release.sh              # 日常构建：核心版本 +1，版本名不变
#   tools/release.sh 1.1.0        # 发行版：版本名改成 1.1.0 + 核心版本 +1 + 打 tag
#   tools/release.sh 1.1.0 "说明"  # 同上，顺带写进 commit message
#
# 环境变量：
#   MCP_TOKEN   手机 MCP 服务的访问令牌（不设就跳过推送到手机）
#   MCP_UPLOAD  上传地址，默认 http://127.0.0.1:8720/upload
#   MCP_DIR     手机目标目录，默认 /storage/emulated/0/xtt/app/mcp
#   GRADLE      gradle 命令，默认 gradle

set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-gradle}"
APK="app/build/outputs/apk/release/app-release.apk"
UPLOAD="${MCP_UPLOAD:-http://127.0.0.1:8720/upload}"
OUTDIR="${MCP_DIR:-/storage/emulated/0/xtt/app/mcp}"

NEW_NAME="${1:-}"
NOTE="${2:-}"

CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
CODE=$((CODE + 1))
if [ -n "$NEW_NAME" ]; then NAME="$NEW_NAME"; fi

sed -i "s/versionCode = .*/versionCode = $CODE/" app/build.gradle.kts
sed -i "s/versionName = \".*\"/versionName = \"$NAME\"/" app/build.gradle.kts
FULL="v$NAME-$CODE"

echo "▶ 构建 $FULL"
$GRADLE :app:assembleRelease

MD5=$(md5sum "$APK" | cut -d' ' -f1)
SIZE=$(du -h "$APK" | cut -f1)
echo "▶ 产物 $SIZE  md5=$MD5"

if [ -n "${MCP_TOKEN:-}" ]; then
    echo "▶ 推送到手机"
    curl -sS -m 300 -X POST --data-binary @"$APK" \
        "$UPLOAD?path=$OUTDIR/MCPBox-$FULL-release.apk&token=$MCP_TOKEN"
    echo
else
    echo "（没设 MCP_TOKEN，跳过推送到手机）"
fi

MSG="release: $FULL"
if [ -n "$NOTE" ]; then MSG="$MSG — $NOTE"; fi
git add -A
git commit -m "$MSG" || echo "（没有需要提交的改动）"

if [ -n "$NEW_NAME" ]; then
    git tag -a "$FULL" -m "$FULL"
    echo "▶ 已打 tag $FULL（推送后 CI 会自动建 Release）"
fi

git push origin main --follow-tags
echo "✓ 完成：$FULL"
