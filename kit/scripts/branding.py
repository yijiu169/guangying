#!/usr/bin/env python3
# 「光影」品牌化：改包名（与电视上已装的 TVBox 共存）、改应用名、换图标
# 用法: python3 branding.py /path/to/TVBox_src
import io, os, re, shutil, sys

DST = sys.argv[1] if len(sys.argv) > 1 else ""
KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NEW_PKG = "com.guangying.tv"
APP_NAME = "光影"

if not DST or not os.path.isdir(os.path.join(DST, "app")):
    print("用法: python3 branding.py /path/to/TVBox_src")
    sys.exit(1)

# ---------- 1. 包名：与现有 TVBox 共存的关键 ----------
gradle = os.path.join(DST, "app/build.gradle")
s = io.open(gradle, encoding="utf-8").read()
m = re.search(r"applicationId\s+['\"]([^'\"]+)['\"]", s)
old_pkg = m.group(1) if m else "(unknown)"
if old_pkg != NEW_PKG:
    s = re.sub(r"(applicationId\s+)['\"][^'\"]+['\"]", r"\1'" + NEW_PKG + "'", s, count=1)
    io.open(gradle, "w", encoding="utf-8", newline="\n").write(s)
    print("  包名: %s -> %s（可与电视上原有 TVBox 共存）" % (old_pkg, NEW_PKG))
else:
    print("  包名已是 %s" % NEW_PKG)

# ---------- 2. 应用名 ----------
strings = os.path.join(DST, "app/src/main/res/values/strings.xml")
if os.path.isfile(strings):
    t = io.open(strings, encoding="utf-8").read()
    t2, n = re.subn(r'(<string\s+name="app_name"\s*>)[^<]*(</string>)', r"\g<1>" + APP_NAME + r"\g<2>", t)
    if n:
        io.open(strings, "w", encoding="utf-8", newline="\n").write(t2)
        print("  应用名 -> %s" % APP_NAME)
    else:
        print("  未找到 app_name，跳过")

# ---------- 3. 图标 / 电视横幅 ----------
icons = {
    "drawable-hdpi": "app_icon.png",
    "drawable-xhdpi": "app_icon.png",
    "drawable-xxhdpi": "app_icon.png",
    "drawable-xxxhdpi": "app_icon.png",
    "drawable": "app_banner.png",
    "drawable-nodpi": "app_banner_hd.png",
}
src_map = {
    "drawable-hdpi/app_icon.png": "app_icon_hdpi.png",
    "drawable-xhdpi/app_icon.png": "app_icon_xhdpi.png",
    "drawable-xxhdpi/app_icon.png": "app_icon_xxhdpi.png",
    "drawable-xxxhdpi/app_icon.png": "app_icon_xxxhdpi.png",
    "drawable/app_banner.png": "app_banner.png",
    "drawable-nodpi/app_banner_hd.png": "app_banner_hd.png",
}
copied = 0
for rel, srcname in src_map.items():
    src = os.path.join(KIT, "app/src/main/res", rel)
    if not os.path.isfile(src):
        continue
    dst_dir = os.path.join(DST, "app/src/main/res", os.path.dirname(rel))
    os.makedirs(dst_dir, exist_ok=True)
    shutil.copyfile(src, os.path.join(DST, "app/src/main/res", rel))
    copied += 1
print("  图标/横幅已替换: %d 个文件" % copied)

# ---------- 4. 顺手确认更新模块指向本仓库 ----------
print("  完成：%s（%s）" % (APP_NAME, NEW_PKG))
