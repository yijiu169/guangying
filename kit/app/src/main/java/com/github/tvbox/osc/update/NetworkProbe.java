package com.github.tvbox.osc.update;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 网络环境探测。
 *
 * 电视盒子/投影常见的两种异常：
 *   1. 连了 WiFi 但没有真正出网（假连接）；
 *   2. github.com / api.github.com 被 DNS 污染或阻断，其它站点正常。
 * 所以既看系统网络状态，也做真实的 HTTP 探测。
 */
public final class NetworkProbe {

    private NetworkProbe() {
    }

    /** 系统层面是否有可用网络 */
    public static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true; // 拿不到状态时不阻拦更新流程，交给真实探测判断
        }
    }

    /** 当前是否移动网络（用于「仅 WiFi 下载」策略） */
    public static boolean isMobile(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.getType() == ConnectivityManager.TYPE_MOBILE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 对指定 URL 做一次轻量探测：只请求头部（必要时读取 1 个字节）。
     *
     * @return true 表示在超时内拿到了 2xx/3xx 响应
     */
    public static boolean probe(String url) {
        return probe(url, UpdateConfig.PROBE_TIMEOUT_MS);
    }

    public static boolean probe(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false); // 只看第一跳，避免被重定向拖慢
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", HttpUtil.UA);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 直连 GitHub 是否可用（决定是否还需要走镜像） */
    public static boolean githubReachable() {
        return probe(UpdateConfig.DIRECT_PROBE);
    }
}
