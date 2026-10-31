package com.github.tvbox.osc.update;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * APK 下载器。
 *
 * 关键点：
 *  - 候选链下载：直连失败 → 镜像，逐个降级，任何一个可用即可完成；
 *  - 断点续传：用 .part 临时文件 + Range 请求，电视盒子网络抖动时不至于从头再来；
 *  - 完整性校验：文件大小 + 可选 SHA-256；
 *  - 免存储权限：写在应用外部私有目录（getExternalFilesDir）。
 */
public final class ApkDownloader {

    public interface Listener {
        /** 开始下载，total <= 0 表示服务端未给大小 */
        void onStart(long total);

        /** 进度回调（后台线程） */
        void onProgress(long done, long total, int percent);

        /** 单个镜像失败，即将换下一个（用于界面上提示「切换线路」） */
        void onRetry(String nextUrl, int index, int total);
    }

    private ApkDownloader() {
    }

    /** 下载目录 */
    public static File dir(Context ctx) {
        File d = null;
        try {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                d = ctx.getExternalFilesDir(UpdateConfig.DIR_NAME);
            }
        } catch (Throwable ignored) {
        }
        if (d == null) d = new File(ctx.getFilesDir(), UpdateConfig.DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File targetFile(Context ctx) {
        return new File(dir(ctx), UpdateConfig.ASSET_APK);
    }

    /**
     * 下载并返回可安装的 APK 文件。
     *
     * @throws IOException 所有线路都失败时抛出
     */
    public static File download(Context ctx, ReleaseInfo info, Listener listener) throws IOException {
        if (info == null || info.apkUrl == null || info.apkUrl.isEmpty()) {
            throw new IOException("下载地址为空");
        }

        File out = targetFile(ctx);

        // 已经下好且大小对得上，直接复用（避免重复下载几十 MB）
        if (out.exists() && info.size > 0 && out.length() == info.size) {
            UpdateLog.i("apk already cached: " + out.length());
            if (listener != null) {
                listener.onStart(info.size);
                listener.onProgress(info.size, info.size, 100);
            }
            return out;
        }

        List<String> chain = buildChain(ctx, info);
        int limit = Math.min(chain.size(), UpdateConfig.MAX_MIRROR_TRY + 1);
        IOException last = null;

        for (int i = 0; i < limit; i++) {
            String url = chain.get(i);
            try {
                UpdateLog.i("download try(" + (i + 1) + "/" + limit + "): " + url);
                downloadOne(url, out, info, listener);
                MirrorResolver.rememberGood(ctx, url);
                UpdateLog.i("download ok via " + url);
                return out;
            } catch (IOException e) {
                last = e;
                UpdateLog.w("download fail: " + url + " -> " + e.getMessage());
                if (listener != null && i + 1 < limit) {
                    listener.onRetry(chain.get(i + 1), i + 1, limit);
                }
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    /**
     * 构造下载候选链，优先级从高到低：
     *   1. 直连（apkUrl）
     *   2. 服务端在 version.json 给的完整 APK 地址（自建 CDN，可完全脱离 GitHub）
     *   3. 服务端给的 GitHub 代理前缀（包裹 apkUrl）
     *   4. 内置镜像链（MIRROR_PREFIX，跳过已加入的直连）
     * 任何一条成功都会被记住，下次优先。
     */
    private static List<String> buildChain(Context ctx, ReleaseInfo info) {
        List<String> chain = new ArrayList<>();
        chain.add(info.apkUrl); // 直连永远第一个

        if (info.apkMirrors != null) {
            for (String u : info.apkMirrors) {
                if (!TextUtils.isEmpty(u) && !chain.contains(u)) chain.add(u);
            }
        }
        if (info.extraMirrors != null) {
            for (String p : info.extraMirrors) {
                String u = MirrorResolver.wrap(p, info.apkUrl);
                if (u != null && !chain.contains(u)) chain.add(u);
            }
        }
        List<String> builtin = MirrorResolver.chain(ctx, info.apkUrl);
        for (int i = 1; i < builtin.size(); i++) {
            String u = builtin.get(i);
            if (!chain.contains(u)) chain.add(u);
        }
        return chain;
    }

    /** 单条线路下载（支持断点续传） */
    private static void downloadOne(String url, File out, ReleaseInfo info, Listener listener) throws IOException {
        File part = new File(out.getAbsolutePath() + ".part");
        long existing = part.exists() ? part.length() : 0L;

        // 服务端给了大小，且 part 已经超过它，说明是脏数据，重下
        if (info.size > 0 && existing > info.size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            existing = 0;
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        RandomAccessFile raf = null;
        try {
            conn = HttpUtil.open(url, true);
            conn.setRequestMethod("GET");
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int code = conn.getResponseCode();
            if (code == 416) { // 续传位置非法，清空重来
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                existing = 0;
                throw new IOException("range not satisfiable, retry from 0");
            }
            if (code != 200 && code != 206) {
                throw new IOException("http " + code);
            }

            boolean append = (code == 206 && existing > 0);
            long total = info.size > 0 ? info.size : conn.getContentLength() + (append ? existing : 0);
            if (total <= 0) total = -1;

            if (listener != null) listener.onStart(total);

            in = conn.getInputStream();
            raf = new RandomAccessFile(part, append ? "rw" : "rws");
            if (append) raf.seek(existing);
            else raf.setLength(0);

            byte[] buf = new byte[64 * 1024];
            long done = append ? existing : 0L;
            long lastTick = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                raf.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (listener != null && (now - lastTick > 200 || (total > 0 && done >= total))) {
                    lastTick = now;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    listener.onProgress(done, total, pct);
                }
            }
            raf.close();
            raf = null;
            closeQuietly(in);
            in = null;

            long realLen = part.length();
            if (info.size > 0 && realLen != info.size) {
                throw new IOException("size mismatch: " + realLen + " != " + info.size);
            }
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (!part.renameTo(out)) {
                throw new IOException("rename failed");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(String.valueOf(t.getMessage()), t);
        } finally {
            closeQuietly(in);
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }

        // SHA-256 校验（Release 提供时）
        if (info.sha256 != null && info.sha256.length() > 0) {
            String actual = sha256(out);
            if (!info.sha256.equalsIgnoreCase(actual)) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new IOException("sha256 mismatch");
            }
            UpdateLog.i("sha256 ok");
        }
    }

    public static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
