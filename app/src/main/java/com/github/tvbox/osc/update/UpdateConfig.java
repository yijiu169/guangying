package com.github.tvbox.osc.update;

/**
 * 自动更新模块配置。
 *
 * 说明：
 *  1. OWNER / REPO 指向「发布 Release（APK）」的仓库。
 *     可直接改这里的默认值，也可在 build.gradle 里加
 *     buildConfigField "String","UPDATE_OWNER","\"xxx\"" 覆盖（本类用反射读取，
 *     即使没有该字段也不会编译失败）。
 *  2. 令牌 **绝不硬编码**。如需提高 GitHub API 限额，在自己的 local.properties 里配置
 *     gh.token=xxxx，由 build.gradle 注入 BuildConfig.GH_TOKEN；local.properties 不入库。
 */
public final class UpdateConfig {

    private UpdateConfig() {
    }

    /* ============================ 仓库 ============================ */

    private static String overrideOwner = null;
    private static String overrideRepo = null;

    /** 运行期覆盖仓库所有者（设置页 / 调试用） */
    public static void setRepo(String owner, String repo) {
        overrideOwner = owner;
        overrideRepo = repo;
    }

    public static String owner() {
        if (overrideOwner != null && !overrideOwner.trim().isEmpty()) return overrideOwner.trim();
        String v = BuildConfigReader.get("UPDATE_OWNER");
        return v.isEmpty() ? "YOUR_GITHUB_NAME" : v;
    }

    public static String repo() {
        if (overrideRepo != null && !overrideRepo.trim().isEmpty()) return overrideRepo.trim();
        String v = BuildConfigReader.get("UPDATE_REPO");
        return v.isEmpty() ? "TVBoxOSC" : v;
    }

    /** Release 中固定命名的元数据资产（由 CI 生成，内容很小） */
    public static final String ASSET_META = "version.json";

    /** Release 中固定命名的 APK 资产（由 CI 生成） */
    public static final String ASSET_APK = "TVBox-latest.apk";

    /* ============================ 行为开关 ============================ */

    /** 是否在每次启动时自动检查更新 */
    public static final boolean CHECK_ON_LAUNCH = true;

    /** 启动后延迟多久开始检查（毫秒），避免和首页加载抢带宽 */
    public static final long LAUNCH_DELAY_MS = 5000L;

    /** 同一版本的启动检查冷却时间（毫秒），避免频繁联网 */
    public static final long LAUNCH_COOLDOWN_MS = 6L * 60L * 60L * 1000L;

    /** 下载完成后是否直接拉起系统安装器（用户要求：下载最新版并启动安装） */
    public static final boolean AUTO_INSTALL = true;

    /** 是否允许在移动网络下下载（电视一般走 WiFi，默认允许） */
    public static final boolean ALLOW_MOBILE_NETWORK = true;

    /* ============================ 网络 ============================ */

    /** 探测单个地址的超时（毫秒）。电视盒子 DNS 往往较慢 */
    public static final int PROBE_TIMEOUT_MS = 3500;

    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 20000;

    /** 下载单个文件时最多尝试的镜像数量 */
    public static final int MAX_MIRROR_TRY = 4;

    /**
     * GitHub 加速镜像前缀（内置兜底）。
     * 用法：前缀 + 原始 GitHub URL，例如
     *   https://gh-proxy.com/https://github.com/owner/repo/releases/latest/download/x.apk
     * 顺序即优先级，前面的失败自动降级到后面的。
     *
     * 注意：这些写死在 APK 里，社区代理经常失效。更灵活的做法是让 version.json
     * 携带 mirrors[]（代理前缀）与 apkMirrors[]（完整地址），服务端随时可换，无需重编。
     */
    public static final String[] MIRROR_PREFIX = new String[]{
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://github.moeyy.xyz/",
            "https://hub.gitmirror.com/",
            "https://gh.llkk.cc/"
    };

    /** 直连探测地址：能通就优先直连，省得走镜像 */
    public static final String DIRECT_PROBE = "https://api.github.com/rate_limit";

    /* ============================ 存储 ============================ */

    /** 下载目录名（应用外部私有目录，免存储权限） */
    public static final String DIR_NAME = "update";

    public static final String SP_NAME = "tvbox_update";
    public static final String SP_LAST_CHECK = "last_check_at";
    public static final String SP_SKIP_VERSION = "skip_version";
    public static final String SP_LAST_GOOD_MIRROR = "last_good_mirror";

    /** 是否输出调试日志 */
    public static final boolean DEBUG = true;

    /* ============================ 内部工具 ============================ */

    /**
     * 反射读取 BuildConfig 字段：字段不存在时返回空串，避免编译期强依赖。
     */
    static final class BuildConfigReader {
        private static final String CLS = "com.github.tvbox.osc.BuildConfig";

        static String get(String field) {
            try {
                Class<?> c = Class.forName(CLS);
                Object v = c.getField(field).get(null);
                return v == null ? "" : String.valueOf(v).trim();
            } catch (Throwable ignore) {
                return "";
            }
        }
    }
}
