package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * APK 安装。
 *
 * 电视端常见坑：
 *  1. Android 7.0+ 必须用 content:// (FileProvider)，直接给 file:// 会 FileUriExposedException；
 *  2. Android 8.0+ 需要「安装未知应用」授权，未授权会静默失败，必须引导到系统设置；
 *  3. 部分电视桌面没有注册 package-archive 的 VIEW，需要回退到 INSTALL_PACKAGE；
 *  4. 少数盒子需要 NEW_TASK，否则从 Activity 上下文拉起会崩。
 */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    /** 是否有「安装未知应用」权限（API < 26 视为有） */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return ctx.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                return true;
            }
        }
        return true;
    }

    /** 跳转「安装未知应用」授权页 */
    public static void openUnknownSourcesSetting(Context ctx) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UpdateLog.e("open unknown sources setting failed", t);
        }
    }

    /**
     * 拉起系统安装器。
     *
     * @return true 表示已成功拉起安装界面
     */
    public static boolean install(Context ctx, File apk) {
        if (apk == null || !apk.exists() || apk.length() <= 0) {
            UpdateLog.w("install: apk not found");
            return false;
        }
        if (!canInstall(ctx)) {
            UpdateLog.w("install: no REQUEST_INSTALL_PACKAGES permission, jump to settings");
            openUnknownSourcesSetting(ctx);
            return false;
        }

        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            UpdateLog.i("install intent sent: " + uri);
            return true;
        } catch (Throwable t) {
            UpdateLog.w("ACTION_VIEW failed, try INSTALL_PACKAGE: " + t);
        }

        // 老系统 / 定制桌面的兜底
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            UpdateLog.e("install failed", t);
            return false;
        }
    }

    /**
     * 静默安装（仅限系统签名 / 有 root 的盒子，普通安装走 {@link #install}）。
     * 默认不启用，作为可选能力保留。
     */
    public static boolean silentInstall(File apk) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "pm install -r -d " + apk.getAbsolutePath()
            });
            int code = p.waitFor();
            UpdateLog.i("silent install exit=" + code);
            return code == 0;
        } catch (Throwable t) {
            UpdateLog.w("silent install unavailable: " + t);
            return false;
        }
    }
}
