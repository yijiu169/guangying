package com.github.tvbox.osc.douban;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 豆瓣数据仓库：内存缓存 + 串行请求（避免触发豆瓣限流）+ 主线程回调。
 *
 * 设计要点（对应「先画靶子」的架构）：
 *  - 这一层只负责元数据，与任何播放源无关；
 *  - 换播放源不会影响已缓存的豆瓣数据；
 *  - 豆瓣取不到时可在此接入 TMDB 兜底（见 loadWithFallback 的注释）。
 */
public final class DoubanRepository {

    public interface Callback {
        void onData(List<DoubanItem> list, boolean fromCache);

        void onError(String message);
    }

    private static final int PAGE_SIZE = 40;
    private static final long CACHE_TTL = 10 * 60 * 1000L; // 10 分钟

    private static final Map<String, CacheEntry> CACHE = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DoubanRepository() {
    }

    private static class CacheEntry {
        List<DoubanItem> data;
        long at;

        CacheEntry(List<DoubanItem> d) {
            data = d;
            at = System.currentTimeMillis();
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < CACHE_TTL;
        }
    }

    /**
     * 加载某个分类。命中缓存立即回调（fromCache=true），否则联网。
     */
    public static void load(final String tag, final Callback cb) {
        final String key = TextUtils.isEmpty(tag) ? "热门" : tag;

        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.fresh()) {
            cb.onData(new ArrayList<>(cached.data), true);
            return;
        }

        IO.execute(() -> {
            try {
                List<DoubanItem> list = DoubanApi.list(key, 0, PAGE_SIZE);
                if (list == null || list.isEmpty()) {
                    // 豆瓣没拿到 —— 这里就是接 TMDB 兜底的位置：
                    // list = TmdbApi.discover(key);
                    MAIN.post(() -> cb.onError("豆瓣暂时没有返回数据"));
                    return;
                }
                CACHE.put(key, new CacheEntry(list));
                final List<DoubanItem> result = list;
                MAIN.post(() -> cb.onData(result, false));
            } catch (Throwable t) {
                MAIN.post(() -> cb.onError("加载失败：" + shortMsg(t)));
            }
        });
    }

    /** 强制刷新（下拉 / 切源后手动重试） */
    public static void refresh(String tag, Callback cb) {
        CACHE.remove(tag);
        load(tag, cb);
    }

    private static String shortMsg(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
