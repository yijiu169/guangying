#!/usr/bin/env python3
# 把首页「推荐」Tab 换成豆瓣海报墙（改动最小：只替换一行 fragments.add）
# 用法: python3 integrate-douban-home.py /path/to/TVBox
import io, os, re, sys

dst = sys.argv[1] if len(sys.argv) > 1 else ""
home = os.path.join(dst, "app/src/main/java/com/github/tvbox/osc/ui/activity/HomeActivity.java")
if not os.path.isfile(home):
    print("找不到 HomeActivity.java，请传入 TVBox 源码根目录")
    sys.exit(1)

s = io.open(home, encoding="utf-8").read()
if "DoubanFragment" in s:
    print("已集成过，跳过")
    sys.exit(0)

# import
s = s.replace("import com.github.tvbox.osc.ui.fragment.GridFragment;",
              "import com.github.tvbox.osc.ui.fragment.DoubanFragment;\nimport com.github.tvbox.osc.ui.fragment.GridFragment;", 1)

# 把「推荐」Tab 的两个分支都替换成豆瓣海报墙
before = s
s = s.replace("fragments.add(UserFragment.newInstance(absXml.videoList));",
              "fragments.add(new DoubanFragment());  // 豆瓣海报墙（元数据来自豆瓣，播放仍走各播放源）")
s = s.replace("fragments.add(UserFragment.newInstance(null));",
              "fragments.add(new DoubanFragment());")
if s == before:
    print("未找到 UserFragment 注入点，请手动替换 fragments.add(UserFragment...)"
          " 为 fragments.add(new DoubanFragment());")
    sys.exit(2)

io.open(home, "w", encoding="utf-8").write(s)
print("已把首页「推荐」Tab 替换为豆瓣海报墙")

# 提示 Tab 名称
print("下一步（可选）：把 sortList 里的「推荐」字样改成「豆瓣」，位置在 HomeActivity/相关 ViewModel")
