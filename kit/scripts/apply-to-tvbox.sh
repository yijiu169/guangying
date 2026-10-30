#!/usr/bin/env bash
# 把更新模块应用到一份 TVBox 源码目录
# 用法: ./apply-to-tvbox.sh /path/to/TVBox
set -e

KIT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DST="${1:-}"

if [ -z "$DST" ] || [ ! -d "$DST/app" ]; then
  echo "用法: $0 /path/to/TVBox  （目录下应有 app/ settings.gradle）"
  exit 1
fi

echo "==> kit: $KIT_DIR"
echo "==> dst: $DST"

# 1) 新增 update / douban 包（不覆盖任何原有文件）
mkdir -p "$DST/app/src/main/java/com/github/tvbox/osc"
for pkg in update douban; do
  rm -rf "$DST/app/src/main/java/com/github/tvbox/osc/$pkg"
  cp -r "$KIT_DIR/app/src/main/java/com/github/tvbox/osc/$pkg" "$DST/app/src/main/java/com/github/tvbox/osc/"
  echo "  + $pkg/ 已复制"
done

# 1.1) 界面相关：Fragment / Adapter / layout / drawable
mkdir -p "$DST/app/src/main/java/com/github/tvbox/osc/ui/fragment" \
         "$DST/app/src/main/java/com/github/tvbox/osc/ui/adapter" \
         "$DST/app/src/main/res/layout" "$DST/app/src/main/res/drawable"
cp "$KIT_DIR/app/src/main/java/com/github/tvbox/osc/ui/fragment/DoubanFragment.java"  "$DST/app/src/main/java/com/github/tvbox/osc/ui/fragment/"
cp "$KIT_DIR/app/src/main/java/com/github/tvbox/osc/ui/adapter/DoubanPosterAdapter.java" "$DST/app/src/main/java/com/github/tvbox/osc/ui/adapter/"
cp "$KIT_DIR/app/src/main/res/layout/fragment_douban.xml" "$KIT_DIR/app/src/main/res/layout/item_douban_poster.xml" "$DST/app/src/main/res/layout/"
cp "$KIT_DIR/app/src/main/res/drawable/"*.xml "$DST/app/src/main/res/drawable/"
echo "  + 豆瓣海报墙（Fragment/Adapter/layout/drawable）"

# 2) proguard 规则
cp "$KIT_DIR/app/proguard-update.pro" "$DST/app/proguard-update.pro"
echo "  + proguard-update.pro"

# 3) App.java：自动插入两行（不整体覆盖，避免丢你的改动）
APP="$DST/app/src/main/java/com/github/tvbox/osc/base/App.java"
if [ -f "$APP" ] && ! grep -q "UpdateManager.attach" "$APP"; then
  python3 - "$APP" <<'PY'
import io, sys, re
p = sys.argv[1]
s = io.open(p, encoding="utf-8").read()
if "import com.github.tvbox.osc.update.UpdateManager;" not in s:
    s = s.replace("public class App extends", "import com.github.tvbox.osc.update.UpdateManager;\n\npublic class App extends", 1)
# 在 onCreate 结束前插入
m = re.search(r"public void onCreate\(\)\s*\{", s)
if m:
    # 找到 onCreate 方法体的最后一个分号后插入
    start = m.end()
    depth = 1
    i = start
    while i < len(s) and depth > 0:
        if s[i] == "{": depth += 1
        elif s[i] == "}": depth -= 1
        i += 1
    body_end = i - 1
    s = s[:body_end].rstrip() + "\n        UpdateManager.attach(this);\n    " + s[body_end:]
io.open(p, "w", encoding="utf-8").write(s)
print("  + App.java 已注入 UpdateManager.attach(this)")
PY
else
  echo "  = App.java 已包含 UpdateManager（跳过）"
fi

# 4) build.gradle：追加 buildConfigField / signingConfigs（不覆盖原文件）
BG="$DST/app/build.gradle"
if ! grep -q "UPDATE_OWNER" "$BG"; then
  cp "$BG" "$BG.bak"
  python3 - "$BG" <<'PY'
import io, sys, re
p = sys.argv[1]
s = io.open(p, encoding="utf-8").read()

cfg = '''
/* ===== 自动更新：仓库与版本号（由 apply-to-tvbox.sh 注入）===== */
def ghToken = ""
def ghOwner = "YOUR_GITHUB_NAME"
def ghRepo = "TVBoxOSC"
def localProps = rootProject.file("local.properties")
if (localProps.exists()) {
    def p = new Properties()
    localProps.withInputStream { p.load(it) }
    ghToken = p.getProperty("gh.token", ghToken) ?: ghToken
    ghOwner = p.getProperty("gh.owner", ghOwner) ?: ghOwner
    ghRepo = p.getProperty("gh.repo", ghRepo) ?: ghRepo
}
def vCode = (System.getenv("VERSION_CODE") ?: project.findProperty("VERSION_CODE") ?: "1").toString().toInteger()
def vName = (System.getenv("VERSION_NAME") ?: project.findProperty("VERSION_NAME") ?: "1.0.0").toString()
'''
s = s.replace("android {", cfg + "\nandroid {", 1)

s = re.sub(r"\n\s*versionCode\s+\S+", "\n        versionCode vCode", s, count=1)
s = re.sub(r"\n\s*versionName\s+\S+", "\n        versionName vName\n        buildConfigField \"String\", \"GH_TOKEN\", '\"${ghToken}\"'\n        buildConfigField \"String\", \"UPDATE_OWNER\", '\"${ghOwner}\"'\n        buildConfigField \"String\", \"UPDATE_REPO\", '\"${ghRepo}\"'", s, count=1)

sign = '''
    signingConfigs {
        release {
            def ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile ks
                storePassword System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias System.getenv("KEY_ALIAS") ?: ""
                keyPassword System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {'''
s = s.replace("    buildTypes {", sign, 1)
repl = r'''\1
            proguardFile 'proguard-update.pro'
            signingConfig rootProject.file("release.keystore").exists() ? signingConfigs.release : signingConfigs.debug'''
s = re.sub(r"(release\s*\{[^}]*?minifyEnabled\s+true)", repl, s, count=1)

io.open(p, "w", encoding="utf-8").write(s)
print("  + build.gradle 已注入（备份见 build.gradle.bak）")
PY
else
  echo "  = build.gradle 已包含更新配置（跳过）"
fi

# 5) CI（可选：仅当 kit 里带了「完整工程」版 workflow 时才复制）
if [ -f "$KIT_DIR/.github/workflows/build-release.yml" ]; then
  mkdir -p "$DST/.github/workflows"
  cp "$KIT_DIR/.github/workflows/build-release.yml" "$DST/.github/workflows/build-release.yml"
  echo "  + .github/workflows/build-release.yml"
else
  echo "  = 跳过 workflow（补丁仓库模式，构建由本仓库的 release.yml 负责）"
fi

# 6) 首页接入豆瓣海报墙
python3 "$KIT_DIR/scripts/integrate-douban-home.py" "$DST" || true

# 7) 「光影」品牌化：包名（与已有 TVBox 共存）、应用名、图标
python3 "$KIT_DIR/scripts/branding.py" "$DST"

echo
echo "完成。接下来："
echo "  1. 在 $DST/local.properties 里加 gh.owner / gh.repo"
echo "  2. 生成并放好 release.keystore（见 README 第四节）"
echo "  3. git tag v1.0.0 && git push origin v1.0.0 触发自动构建发布"
