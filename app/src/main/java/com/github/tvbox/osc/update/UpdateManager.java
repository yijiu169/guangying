package com.github.tvbox.osc.update;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动更新总控。
 *
 * 使用方式（只需一行）：
 *   App.onCreate() 里调用 UpdateManager.attach(this);
 * 之后每次进入软件，第一个 Activity 回到前台时就会自动检查更新：
 *   1. 静默请求最新版本（直连 -> 镜像链）；
 *   2. 有新版则显示进度框并自动下载（断点续传、镜像回退）；
 *   3. 下载完成自动拉起系统安装器。
 *
 * 也支持手动检查：UpdateManager.get(ctx).checkManually(activity);
 */
public final class UpdateManager {

    private static volatile UpdateManager sInstance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private volatile boolean launchHookInstalled = false;
    private volatile boolean launchCheckDone = false;

    private WeakReference<Activity> hostRef = new WeakReference<>(null);
    private Dialog dialog;

    private UpdateManager(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static UpdateManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (UpdateManager.class) {
                if (sInstance == null) sInstance = new UpdateManager(ctx);
            }
        }
        return sInstance;
    }

    /**
     * 在 Application.onCreate() 中调用一次：注册 Activity 生命周期，
     * 等首个 Activity 可见后延迟执行一次启动检查。
     */
    public static void attach(Application application) {
        if (application == null) return;
        final UpdateManager um = get(application);
        if (um.launchHookInstalled || !UpdateConfig.CHECK_ON_LAUNCH) return;
        um.launchHookInstalled = true;

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                um.hostRef = new WeakReference<>(activity);
                if (um.launchCheckDone) return;
                um.launchCheckDone = true;
                // 延迟一会儿，别和首页首屏数据抢带宽
                um.main.postDelayed(() -> um.check(activity, false), UpdateConfig.LAUNCH_DELAY_MS);
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                Activity h = um.hostRef.get();
                if (h == activity) um.hostRef = new WeakReference<>(null);
            }
        });
    }

    /** 手动检查（设置页 / 遥控器入口） */
    public void checkManually(Activity activity) {
        check(activity, true);
    }

    public boolean isBusy() {
        return busy.get();
    }

    /* ============================ 主流程 ============================ */

    private void check(final Activity activity, final boolean manual) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        if (!busy.compareAndSet(false, true)) {
            if (manual) UpdateUi.toast(app, "正在检查更新，请稍候");
            return;
        }

        if (!NetworkProbe.isOnline(app)) {
            busy.set(false);
            if (manual) UpdateUi.toast(app, "网络未连接，无法检查更新");
            return;
        }

        if (manual) {
            if (UpdateConfig.ALLOW_MOBILE_NETWORK || !NetworkProbe.isMobile(app)) {
                main.post(() -> showDialog(activity, "正在检查更新", "正在连接版本服务器…", true));
            }
        }

        io.execute(() -> {
            try {
                doCheck(activity, manual);
            } catch (Throwable t) {
                UpdateLog.e("check crash", t);
                final String msg = "检查更新失败：" + shortMsg(t);
                main.post(() -> {
                    dismissDialog();
                    if (manual) UpdateUi.toast(app, msg);
                    busy.set(false);
                });
            }
        });
    }

    private void doCheck(Activity activity, boolean manual) {
        // 启动检查的冷却：同一版本 6 小时内不重复联网
        if (!manual && isInLaunchCooldown()) {
            UpdateLog.i("launch check skipped by cooldown");
            busy.set(false);
            return;
        }

        UpdateLog.i("checking latest release ...");
        ReleaseInfo info = UpdateApi.fetchLatest(app);
        markChecked(manual);

        if (info == null) {
            main.post(() -> {
                dismissDialog();
                if (manual) UpdateUi.toast(app, "检查失败：无法连接版本服务器（已尝试镜像）");
                busy.set(false);
            });
            return;
        }

        if (!isNewer(info)) {
            UpdateLog.i("already latest: " + info.versionName);
            main.post(() -> {
                dismissDialog();
                if (manual) UpdateUi.toast(app, "已是最新版本 " + currentVersionName());
                busy.set(false);
            });
            return;
        }

        UpdateLog.i("new version found: " + info);
        main.post(() -> {
            Activity act = alive(activity);
            if (act == null) {
                busy.set(false);
                return;
            }
            Dialog d = dialog;
            if (d == null) {
                d = showDialog(act, "发现新版本 " + info.versionName,
                        "更新说明加载中…  文件大小 " + UpdateUi.size(info.size), false);
            } else {
                UpdateUi.holder(d).setTitle("发现新版本 " + info.versionName);
            }
            UpdateUi.setIndeterminate(d, "准备下载…");
            startDownload(act, info, d);
        });
    }

    private void startDownload(Activity activity, ReleaseInfo info, Dialog d) {
        io.execute(() -> {
            try {
                File apk = ApkDownloader.download(app, info, new ApkDownloader.Listener() {
                    @Override
                    public void onStart(long total) {
                        main.post(() -> UpdateUi.setProgress(d, 0, "开始下载 " + UpdateUi.size(total)));
                    }

                    @Override
                    public void onProgress(long done, long total, int percent) {
                        final int pct = percent;
                        main.post(() -> UpdateUi.setProgress(d, pct,
                                UpdateUi.size(done) + " / " + UpdateUi.size(total) + " · 下载中，请保持网络畅通"));
                    }

                    @Override
                    public void onRetry(String nextUrl, int index, int total) {
                        main.post(() -> UpdateUi.setIndeterminate(d, "直连不稳定，正在切换线路（" + (index + 1) + "/" + total + "）…"));
                    }
                });

                UpdateLog.i("download finished: " + apk.getAbsolutePath());
                main.post(() -> {
                    UpdateUi.setProgress(d, 100, "下载完成，正在启动安装…");
                    dismissDialog();
                    busy.set(false);
                    if (UpdateConfig.AUTO_INSTALL) {
                        boolean ok = ApkInstaller.install(app, apk);
                        if (!ok) {
                            UpdateUi.toast(app, "已下载安装包，请在「设置-关于」中手动安装，或授予安装未知应用权限");
                        }
                    } else {
                        UpdateUi.toast(app, "新版本已下载：" + apk.getName());
                    }
                });
            } catch (Throwable t) {
                UpdateLog.e("download failed", t);
                final String msg = "下载失败：" + shortMsg(t);
                main.post(() -> {
                    dismissDialog();
                    UpdateUi.toast(app, msg + "（已自动尝试直连与镜像）");
                    busy.set(false);
                });
            }
        });
    }

    /* ============================ 版本比较 ============================ */

    private boolean isNewer(ReleaseInfo info) {
        int curCode = currentVersionCode();
        if (info.versionCode > 0) {
            return info.versionCode > curCode;
        }
        SemVer remote = SemVer.parse(TextUtils.isEmpty(info.versionName) ? info.tag : info.versionName);
        SemVer local = SemVer.parse(currentVersionName());
        boolean newer = remote.isNewerThan(local);
        UpdateLog.i("compare semver remote=" + remote + " local=" + local + " newer=" + newer);
        return newer;
    }

    public String currentVersionName() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    public int currentVersionCode() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ============================ 冷却与缓存 ============================ */

    private SharedPreferences sp() {
        return app.getSharedPreferences(UpdateConfig.SP_NAME, Context.MODE_PRIVATE);
    }

    private boolean isInLaunchCooldown() {
        long last = sp().getLong(UpdateConfig.SP_LAST_CHECK, 0L);
        return last > 0 && System.currentTimeMillis() - last < UpdateConfig.LAUNCH_COOLDOWN_MS;
    }

    private void markChecked(boolean manual) {
        if (manual) return; // 手动检查不受冷却影响
        sp().edit().putLong(UpdateConfig.SP_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    /** 忽略某个版本（供 UI 扩展用） */
    public void skipVersion(String version) {
        sp().edit().putString(UpdateConfig.SP_SKIP_VERSION, version == null ? "" : version).apply();
    }

    public String skippedVersion() {
        return sp().getString(UpdateConfig.SP_SKIP_VERSION, "");
    }

    /* ============================ UI 小工具 ============================ */

    private Dialog showDialog(Activity activity, String title, String sub, boolean indeterminate) {
        Dialog d = dialog;
        if (d != null && d.isShowing()) {
            UpdateUi.holder(d).setTitle(title);
            if (indeterminate) UpdateUi.setIndeterminate(d, sub);
            else UpdateUi.setProgress(d, 0, sub);
            return d;
        }
        try {
            d = UpdateUi.create(activity, title, sub);
            d.show();
            dialog = d;
            if (indeterminate) UpdateUi.setIndeterminate(d, sub);
            return d;
        } catch (Throwable t) {
            UpdateLog.e("show dialog failed", t);
            return null;
        }
    }

    private void dismissDialog() {
        Dialog d = dialog;
        dialog = null;
        UpdateUi.close(d);
    }

    private Activity alive(Activity fallback) {
        Activity h = hostRef.get();
        if (h != null && !h.isFinishing()) return h;
        if (fallback != null && !fallback.isFinishing()) return fallback;
        return null;
    }

    private static String shortMsg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return t.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
