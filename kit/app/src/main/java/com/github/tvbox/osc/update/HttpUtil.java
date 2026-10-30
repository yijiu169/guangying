package com.github.tvbox.osc.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 HTTP 工具（只依赖 HttpURLConnection，避免引入额外依赖）。
 */
final class HttpUtil {

    static final String UA = "TVBox-Update/1.0 (Android; TV)";

    private HttpUtil() {
    }

    /** 读取 BuildConfig.GH_TOKEN（可为空，用于提高 API 限额，不写死在源码里） */
    static String token() {
        try {
            Class<?> c = Class.forName("com.github.tvbox.osc.BuildConfig");
            Object v = c.getField("GH_TOKEN").get(null);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable ignore) {
            return "";
        }
    }

    static HttpURLConnection open(String url, boolean followRedirect) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(UpdateConfig.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(UpdateConfig.READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(followRedirect);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Encoding", "identity"); // 免去手动解 gzip
        conn.setRequestProperty("Cache-Control", "no-cache");

        // 安全：token 只在访问 GitHub 官方域名时发送，绝不随请求发往第三方镜像站
        String tk = token();
        if (!tk.isEmpty() && isGithubHost(url)) {
            conn.setRequestProperty("Authorization", "token " + tk);
        }
        return conn;
    }

    /** 是否 GitHub 官方域名（用于决定要不要带 token） */
    static boolean isGithubHost(String url) {
        try {
            String host = new URL(url).getHost();
            return "github.com".equalsIgnoreCase(host) || "api.github.com".equalsIgnoreCase(host);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取文本（用于 JSON / 小型元数据），超过 maxBytes 直接放弃 */
    static String getText(String url, int maxBytes) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = open(url, true);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                UpdateLog.w("GET " + url + " -> " + code);
                return null;
            }
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    UpdateLog.w("meta too large: " + url);
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            UpdateLog.w("GET fail " + url + " : " + t);
            return null;
        } finally {
            close(in);
            if (conn != null) conn.disconnect();
        }
    }

    static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
