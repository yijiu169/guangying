package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像策略。
 *
 * 电视盒子直连 GitHub 很常见地会失败（DNS 污染 / 运营商阻断 / 跨境抖动），
 * 因此下载与元数据都统一走「候选链」：
 *   直连 → 上次成功的镜像 → 其余镜像
 * 任何一个成功都会记录下来，下次优先使用，越用越快。
 */
public final class MirrorResolver {

    private MirrorResolver() {
    }

    /** 把原始 GitHub 链接包成候选链（直连在最前，其余为镜像） */
    public static List<String> chain(String originUrl) {
        List<String> list = new ArrayList<>();
        if (originUrl == null || originUrl.isEmpty()) return list;
        list.add(originUrl);
        for (String prefix : UpdateConfig.MIRROR_PREFIX) {
            String url = wrap(prefix, originUrl);
            if (url != null && !list.contains(url)) list.add(url);
        }
        return list;
    }

    /**
     * 按「上次成功镜像优先」重排候选链。
     */
    public static List<String> chain(Context ctx, String originUrl) {
        List<String> base = chain(originUrl);
        String last = lastGood(ctx);
        if (last == null || last.isEmpty() || base.size() < 2) return base;

        String preferred = wrap(last, originUrl);
        if (preferred == null) return base;

        List<String> ordered = new ArrayList<>();
        ordered.add(base.get(0)); // 直连永远第一
        if (!ordered.contains(preferred)) ordered.add(preferred);
        for (String s : base) {
            if (!ordered.contains(s)) ordered.add(s);
        }
        return ordered;
    }

    public static String wrap(String prefix, String originUrl) {
        if (prefix == null || prefix.isEmpty()) return originUrl;
        if (originUrl == null || originUrl.isEmpty()) return originUrl;
        if (!prefix.endsWith("/")) prefix = prefix + "/";
        return prefix + originUrl;
    }

    /* ---------------- 记录上次成功的镜像 ---------------- */

    public static void rememberGood(Context ctx, String finalUrl) {
        try {
            String prefix = guessPrefix(finalUrl);
            SharedPreferences sp = ctx.getSharedPreferences(UpdateConfig.SP_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(UpdateConfig.SP_LAST_GOOD_MIRROR, prefix).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String lastGood(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(UpdateConfig.SP_NAME, Context.MODE_PRIVATE);
            return sp.getString(UpdateConfig.SP_LAST_GOOD_MIRROR, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String guessPrefix(String url) {
        if (url == null) return "";
        for (String p : UpdateConfig.MIRROR_PREFIX) {
            if (url.startsWith(p)) return p;
        }
        return ""; // 直连
    }
}
