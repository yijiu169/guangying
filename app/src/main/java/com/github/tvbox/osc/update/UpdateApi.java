package com.github.tvbox.osc.update;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 版本信息来源。
 *
 * 通道 A（首选，最省流量，也不依赖 api.github.com）：
 *   https://github.com/{owner}/{repo}/releases/latest/download/version.json
 *
 * 通道 B（兜底，任何符合 GitHub Release 规范的仓库都能用）：
 *   https://api.github.com/repos/{owner}/{repo}/releases/latest
 *
 * 两条通道对外都经过「直连 + 镜像」候选链，任何一个能通就能拿到版本信息。
 */
public final class UpdateApi {

    private static final int META_MAX_BYTES = 512 * 1024;

    private UpdateApi() {
    }

    /** 拉取最新版本信息；失败返回 null（不抛异常） */
    public static ReleaseInfo fetchLatest(Context ctx) {
        ReleaseInfo info = fromVersionJson(ctx);
        if (info != null) return info;
        return fromGithubApi(ctx);
    }

    /* ==================== 通道 A：固定名 version.json ==================== */

    private static ReleaseInfo fromVersionJson(Context ctx) {
        String origin = String.format("https://github.com/%s/%s/releases/latest/download/%s",
                UpdateConfig.owner(), UpdateConfig.repo(), UpdateConfig.ASSET_META);

        List<String> chain = MirrorResolver.chain(ctx, origin);
        int tried = 0;
        for (String url : chain) {
            if (tried++ >= UpdateConfig.MAX_MIRROR_TRY + 1) break;
            String text = HttpUtil.getText(url, META_MAX_BYTES);
            if (TextUtils.isEmpty(text)) continue;
            try {
                JSONObject o = new JSONObject(text);
                ReleaseInfo info = new ReleaseInfo();
                info.versionName = optString(o, "versionName", optString(o, "tag", ""));
                info.tag = optString(o, "tag", info.versionName);
                info.versionCode = o.optInt("versionCode", -1);
                info.notes = optString(o, "notes", "");
                info.size = o.optLong("size", 0L);
                info.sha256 = optString(o, "sha256", "");
                info.force = o.optBoolean("force", false);
                info.publishedAt = optString(o, "publishedAt", "");
                info.source = url.startsWith(UpdateConfig.MIRROR_PREFIX[0]) ? "mirror" : "direct";

                String apkName = optString(o, "apk", UpdateConfig.ASSET_APK);
                if (TextUtils.isEmpty(apkName)) apkName = UpdateConfig.ASSET_APK;
                info.apkUrl = String.format(
                        "https://github.com/%s/%s/releases/latest/download/%s",
                        UpdateConfig.owner(), UpdateConfig.repo(), apkName);

                if (TextUtils.isEmpty(info.versionName) && info.versionCode <= 0) continue;
                MirrorResolver.rememberGood(ctx, url);
                UpdateLog.i("version.json ok: " + info + " via " + url);
                return info;
            } catch (Throwable t) {
                UpdateLog.w("version.json parse fail: " + t);
            }
        }
        return null;
    }

    /* ==================== 通道 B：GitHub API ==================== */

    private static ReleaseInfo fromGithubApi(Context ctx) {
        String origin = String.format("https://api.github.com/repos/%s/%s/releases/latest",
                UpdateConfig.owner(), UpdateConfig.repo());

        List<String> chain = MirrorResolver.chain(ctx, origin);
        int tried = 0;
        for (String url : chain) {
            if (tried++ >= UpdateConfig.MAX_MIRROR_TRY + 1) break;
            String text = HttpUtil.getText(url, META_MAX_BYTES);
            if (TextUtils.isEmpty(text)) continue;
            try {
                JSONObject o = new JSONObject(text);
                if (o.has("message")) { // 404 / 403 之类
                    UpdateLog.w("api message: " + o.optString("message"));
                    continue;
                }
                ReleaseInfo info = new ReleaseInfo();
                info.tag = optString(o, "tag_name", "");
                info.versionName = info.tag;
                info.notes = optString(o, "body", "");
                info.publishedAt = optString(o, "published_at", "");
                info.source = "api";

                JSONArray assets = o.optJSONArray("assets");
                JSONObject picked = null;
                JSONObject firstApk = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = optString(a, "name", "");
                        if (UpdateConfig.ASSET_APK.equalsIgnoreCase(name)) {
                            picked = a;
                            break;
                        }
                        if (firstApk == null && name.toLowerCase().endsWith(".apk")) firstApk = a;
                    }
                }
                if (picked == null) picked = firstApk;
                if (picked == null) continue;

                info.apkUrl = optString(picked, "browser_download_url", "");
                info.size = picked.optLong("size", 0L);
                info.versionCode = info.publishedAt.isEmpty() ? -1 : -1;
                if (TextUtils.isEmpty(info.versionName) || TextUtils.isEmpty(info.apkUrl)) continue;

                MirrorResolver.rememberGood(ctx, url);
                UpdateLog.i("api ok: " + info + " via " + url);
                return info;
            } catch (Throwable t) {
                UpdateLog.w("api parse fail: " + t);
            }
        }
        return null;
    }

    private static String optString(JSONObject o, String key, String def) {
        String v = o.optString(key, def);
        return v == null ? def : v;
    }
}
