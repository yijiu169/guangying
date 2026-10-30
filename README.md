# TVBox 自动更新模块（Update Kit）

给 TVBox（`com.github.tvbox.osc`）加一套**启动即检查、自动下载、自动拉起安装**的升级能力，
并解决电视盒子直连 GitHub 常失败的问题：**自动探测网络 + 镜像回退 + 断点续传**。

---

## 一、它做了什么

| 环节 | 行为 |
|---|---|
| 启动检查 | `Application` 注册生命周期，首个界面可见 5 秒后静默检查一次（同一版本 6 小时内不重复联网） |
| 版本来源 | ① Release 里的 `version.json`（体积小、省流量）② `api.github.com/releases/latest`（兜底，任意 GitHub 仓库通用） |
| 网络适配 | 先探测 `api.github.com` 是否直连可达；不可达则整条链路（元数据 + APK）自动走镜像 |
| 镜像策略 | 6 个加速前缀候选，**上次成功的线路优先**，一个失败自动换下一个，最多试 4 条 |
| 下载 | 断点续传（`.part` + `Range`）、200ms 节流进度回调、大小校验、可选 SHA-256 校验 |
| 安装 | `FileProvider` + `ACTION_VIEW`，Android 8+ 自动检查「安装未知应用」权限并引导授权，电视桌面无对应组件时回退 `ACTION_INSTALL_PACKAGE` |
| 存盘 | 应用外部私有目录 `Android/data/<pkg>/files/update/`，**免存储权限** |

## 二、目录结构

```
app/src/main/java/com/github/tvbox/osc/update/
├── UpdateConfig.java     配置：仓库、镜像列表、超时、开关
├── UpdateManager.java    总控：检查 → 比较 → 下载 → 安装（含生命周期自动触发）
├── UpdateApi.java        版本信息（version.json 通道 + API 通道）
├── SemVer.java           版本号解析与比较（支持 1.0.9-alpha01）
├── ReleaseInfo.java      版本数据模型
├── NetworkProbe.java     网络环境探测（系统状态 + 真实 HTTP 探测）
├── MirrorResolver.java   镜像候选链与「上次成功线路」记忆
├── HttpUtil.java         极简 HTTP（HttpURLConnection，零额外依赖）
├── ApkDownloader.java    下载器（断点续传 / 镜像降级 / 校验）
├── ApkInstaller.java     安装器（FileProvider / 未知来源 / TV 兜底）
├── UpdateUi.java         电视友好的进度对话框（代码构建，不新增 layout 资源）
└── UpdateLog.java        日志（`adb logcat -s TVBoxUpdate`）

.github/workflows/build-release.yml   打 tag 自动构建 + 发布 Release
app/build.gradle                      已注入版本号/仓库配置/签名
app/proguard-update.pro               混淆保留规则
```

## 三、集成到 TVBox 源码（3 步）

假设你已经有一份 TVBox 源码（`q215613905/TVBoxOS` 或自己的 fork）。

```bash
# 1. 把本 kit 覆盖进去（同名文件会被替换：build.gradle 与 App.java 都是已在原文件基础上改好的）
cp -r tvbox-update-kit/app/.            /path/to/TVBox/app/
cp -r tvbox-update-kit/.github/.        /path/to/TVBox/.github/

# 2. 配置仓库与（可选）token，写在 local.properties，不入库
cat >> /path/to/TVBox/local.properties <<'EOF'
gh.owner=你的GitHub用户名
gh.repo=TVBoxOSC
# 可选：仅用于提高 GitHub API 限额，不填也能跑
# gh.token=ghp_xxx
EOF

# 3. 编译
cd /path/to/TVBox && ./gradlew :app:assembleJavaRelease
```

> 如果你不想覆盖 `build.gradle` / `App.java`，也可以手动改：
> - `App.java` 加两行：`import ...UpdateManager;` 和 `UpdateManager.attach(this);`（放在 `onCreate` 末尾）
> - `build.gradle` 加 `versionCode/versionName` 变量、3 个 `buildConfigField`、`signingConfigs`、`proguardFile`

**唯一必须的 AndroidManifest 项，原工程已经有了：**

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<provider android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider" ... >
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
</provider>
```

`file_paths.xml` 里已有 `<external-path path="." />`，覆盖了下载目录，无需改动。

## 四、发布（自动出 APK + 自动更新闭环）

### 1. 准备签名（只需一次）

升级必须签名一致，否则装不上。生成并保存好：

```bash
keytool -genkeypair -v -keystore release.keystore -alias tvbox \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=TVBox, OU=Dev, O=TVBox, L=CN, S=CN, C=CN"

# 转成 base64 用于 GitHub Secrets
base64 -w0 release.keystore > keystore.b64
```

> `release.keystore` **不要提交到仓库**，加到 `.gitignore`。

### 2. 配置 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Name | 值 |
|---|---|
| `KEYSTORE_BASE64` | `keystore.b64` 的内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | `tvbox` |
| `KEY_PASSWORD` | key 密码 |
| `GH_TOKEN` | 可选，构建期访问 API 用 |

### 3. 发布一个版本

```bash
git tag v1.0.9
git push origin v1.0.9
```

Actions 会：
1. 计算 `versionCode`（`yyMMddHH`，天然递增）与 `versionName`（取 tag）
2. 构建 `assembleJavaRelease`
3. 生成 `version.json`（含 size / sha256）
4. 发布 Release，附带 `TVBox-latest.apk` + `version.json`

**客户端下一次启动就会自动读到这个 Release 并升级**，无需再改代码。

## 五、为什么电视上直连失败也能更新

```
检查版本
  ├─ 探测 api.github.com（3.5s）
  │    ├─ 通 → 直连拉 version.json
  │    └─ 不通 → https://gh-proxy.com/https://github.com/... 依此类推
  └─ 拿到版本 → 下载 APK
       ├─ 直连失败 → 换镜像 1 → 镜像 2 …
       ├─ 记录成功线路，下次优先
       └─ 断点续传，失败不清零
```

## 六、常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 提示「检查失败：无法连接版本服务器」 | 镜像全挂或仓库没有 Release。先确认浏览器能打开 `https://github.com/<owner>/<repo>/releases/latest` |
| 下载完没有自动弹出安装 | 盒子没给「安装未知应用」权限，会跳到系统设置，授权后再点一次更新 |
| 安装时报「应用未安装 / 签名不一致」 | 换了 keystore。必须用当初打包的同一个 keystore |
| 想强制每次更新 | `UpdateConfig.AUTO_INSTALL`/`force` 字段；Release 的 `version.json` 里 `force:true` |
| 调试 | `adb logcat -s TVBoxUpdate` |

## 七、安全说明

- **源码中没有任何 token**。`GH_TOKEN` 从 `local.properties` 注入 `BuildConfig`，该文件不入库。
- 若曾把 token 贴到聊天/截图/提交里，请立刻到 GitHub 撤销并重新生成。
- 下载的 APK 若 Release 提供 `sha256`，会自动校验后再安装。

---

# 附：豆瓣海报墙（UI 落地 · 第一阶段）

除了自动更新，本 kit 还包含首页海报墙的 Android 实现（对应之前 HTML 原型的设计）：
**元数据全部来自豆瓣，播放仍然走你自己的播放源**，两者彻底解耦。

## 文件

```
app/src/main/java/com/github/tvbox/osc/douban/
├── DoubanItem.java        条目模型（id/片名/评分/年份/封面/简介）
├── DoubanApi.java         豆瓣接口（j/search_subjects + 详情解析 + 封面代理）
├── DoubanHttp.java        带 UA/Referer 的极简 HTTP
└── DoubanRepository.java  内存缓存（10 分钟）+ 串行队列防限流 + 主线程回调

app/src/main/java/com/github/tvbox/osc/ui/
├── fragment/DoubanFragment.java      海报墙页面（分类标签 + 5 列网格）
└── adapter/DoubanPosterAdapter.java  焦点放大 1.08 + 上浮

app/src/main/res/
├── layout/fragment_douban.xml
├── layout/item_douban_poster.xml
└── drawable/  bg_poster_*.xml  sel_poster_frame.xml  sel_douban_tag*.xml
```

## 接入首页（一条命令）

```bash
python3 scripts/integrate-douban-home.py /path/to/TVBox
```

它只做两件事：加一行 import、把首页「推荐」Tab 的 `fragments.add(UserFragment...)`
换成 `fragments.add(new DoubanFragment())`。**不改动 Tab 结构、不动播放链路**，
所以即使豆瓣挂了，其它 Tab 和播放都照常。

## 关键设计

| 点 | 处理 |
|---|---|
| 豆瓣限流 | 10 分钟内存缓存 + 单线程串行请求，切分类命中缓存立即出图 |
| 封面 403 | 豆瓣图片有 Referer 防盗链，统一走 `images.weserv.nl` 代理 |
| 拿不到数据 | `DoubanRepository.load` 里预留了 `TmdbApi.discover(key)` 兜底位 |
| 点击海报 | 用片名跳 TVBox 现有搜索页，由它去所有播放源找资源（元数据≠播放源） |
| 电视焦点 | item 真实获焦 + 整卡放大上浮，边框走 `sel_poster_frame`（选中白边 2dp，贴边不游离） |

## 下一阶段（待做）

- 详情页：豆瓣剧照 + 评分 + 简介 + 选集（原型 `#view-detail`）
- 播放器：现有 Exo/IJK 上套 Infuse 风格控件
- 搜索：拼音首字母 + 搜索建议
