package com.github.tvbox.osc.douban;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.BuildConfig;

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
                    if (BuildConfig.PREVIEW_MODE) {
                        final List<DoubanItem> demo = previewData();
                        MAIN.post(() -> cb.onData(demo, false));
                        return;
                    }
                    MAIN.post(() -> cb.onError("豆瓣暂时没有返回数据"));
                    return;
                }
                CACHE.put(key, new CacheEntry(list));
                final List<DoubanItem> result = list;
                MAIN.post(() -> cb.onData(result, false));
            } catch (Throwable t) {
                if (BuildConfig.PREVIEW_MODE) {
                    final List<DoubanItem> demo = previewData();
                    MAIN.post(() -> cb.onData(demo, false));
                    return;
                }
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

    /* ===== 预览用样例数据（仅 -PpreviewNoNative 构建生效） =====
       模拟器/无网环境无法访问豆瓣时，用它验证界面布局与焦点手感；
       正式包 PREVIEW_MODE=false，这条分支不会被执行。 */
    private static List<DoubanItem> previewData() {
        String[][] rows = {
                {"黑球", "8.6", "2026"}, {"封神第二部", "7.8", "2025"}, {"热辣滚烫", "7.6", "2024"},
                {"第二十条", "7.7", "2024"}, {"飞驰人生2", "8.0", "2024"}, {"年会不能停", "8.1", "2023"},
                {"流浪地球2", "8.3", "2023"}, {"长安三万里", "8.3", "2023"}, {"宇宙探索编辑部", "8.4", "2023"},
                {"消失的她", "6.5", "2023"}, {"八角笼中", "7.4", "2023"}, {"周处除三害", "8.1", "2023"},
                {"沙丘2", "8.2", "2024"}, {"奥本海默", "8.8", "2023"}, {"芭比", "8.0", "2023"},
                {"疯狂的麦克斯：狂暴女神", "7.4", "2024"}, {"哥斯拉大战金刚2", "6.6", "2024"},
                {"默杀", "6.9", "2024"}, {"默读", "8.5", "2024"}, {"被我弄丢的你", "6.1", "2024"},
                {"抓娃娃", "7.4", "2024"}, {"野孩子", "7.2", "2024"}, {"只此青绿", "8.9", "2024"},
                {"头脑特工队2", "8.0", "2024"}
        };
        List<DoubanItem> list = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            DoubanItem it = new DoubanItem();
            it.id = "demo" + i;
            it.title = rows[i][0];
            it.rate = rows[i][1];
            it.year = rows[i][2];
            it.subtitle = "剧情 / 悬疑";
            it.from = "demo";
            list.add(it);
        }
        return list;
    }
}
