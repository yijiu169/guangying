#!/usr/bin/env bash
# 一键发布：创建/推送仓库 -> 打 tag -> 触发 GitHub Actions 构建并发布 Release
#
# 用法：
#   export GH_TOKEN=ghp_xxx        # 需要 repo 权限；请只在自己的机器上使用
#   export GH_OWNER=yourname
#   export GH_REPO=TVBoxOSC
#   cd /path/to/TVBox && /path/to/tvbox-update-kit/scripts/publish.sh v1.0.0
set -e

TAG="${1:-v1.0.0}"
: "${GH_TOKEN:?请先 export GH_TOKEN}"
: "${GH_OWNER:?请先 export GH_OWNER}"
: "${GH_REPO:?请先 export GH_REPO}"

API="https://api.github.com"
AUTH=(-H "Authorization: token ${GH_TOKEN}" -H "Accept: application/vnd.github+json")

echo "==> 检查仓库 ${GH_OWNER}/${GH_REPO}"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${API}/repos/${GH_OWNER}/${GH_REPO}")
if [ "$CODE" = "404" ]; then
  echo "    仓库不存在，创建一个私有仓库"
  curl -s -X POST "${AUTH[@]}" "${API}/user/repos" \
    -d "{\"name\":\"${GH_REPO}\",\"private\":true,\"description\":\"TVBox with auto-update\"}" > /dev/null
  sleep 2
elif [ "$CODE" != "200" ]; then
  echo "    访问仓库失败: HTTP $CODE（检查 token 权限）"
  exit 1
fi

echo "==> 初始化 git"
git init -q 2>/dev/null || true
git checkout -q -b main 2>/dev/null || git branch -M main 2>/dev/null || true

cat > .gitignore <<'IGN'
# 构建产物与本地密钥，绝不入库
*.iml
.gradle/
build/
local.properties
release.keystore
*.jks
*.keystore
keystore.b64
.idea/
IGN

git add -A
git -c user.name="tvbox-ci" -c user.email="ci@local" commit -q -m "TVBox with auto-update module" || echo "    无新改动"

echo "==> 推送"
git remote remove origin 2>/dev/null || true
git remote add origin "https://${GH_TOKEN}@github.com/${GH_OWNER}/${GH_REPO}.git"
git push -u origin main --force

echo "==> 打 tag ${TAG} 触发构建"
git tag -f "$TAG"
git push origin "$TAG" --force

echo
echo "完成。到 https://github.com/${GH_OWNER}/${GH_REPO}/actions 查看构建进度"
echo "构建成功后会生成 Release，客户端下次启动即可自动更新。"
echo
echo "提醒：命令里带了 token，shell 历史里请自行清理。"
