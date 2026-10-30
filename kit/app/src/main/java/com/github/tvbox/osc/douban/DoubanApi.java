package com.github.tvbox.osc.douban;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 豆瓣数据接口（只取元数据：海报 / 评分 / 片名 / 简介）。
 *
 * 说明：
 *  - 豆瓣没有公开 API，这里用的是网页端同源的 j/search_subjects 接口；
 *  - 必须带 Referer 与 UA，否则会被判为爬虫；
 *  - 封面有防盗链，统一走 {@link #proxyImage(String)} 代理；
 *  - 豆瓣限流较严，调用方请配合 {@link DoubanRepository} 的缓存与串行队列。
 */
public final class DoubanApi {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Safari/537.36";

    private static final String REFERER = "https://movie.douban.com/";

    /** 首页可选的分类 */
    public static final String[] TAGS = new String[]{
            "热门", "最新", "豆瓣高分", "华语", "欧美", "韩国", "日本", "动画", "纪录片"
    };

    private DoubanApi() {
    }

    /* ==================== 列表 ==================== */

    /**
     * 拉取某个分类的条目。
     *
     * @param tag   分类（见 {@link #TAGS}）
     * @param start 起始序号
     * @param limit 数量（豆瓣单页上限 100）
     */
    public static List<DoubanItem> list(String tag, int start, int limit) throws Exception {
        if (TextUtils.isEmpty(tag)) tag = "热门";
        String url = "https://movie.douban.com/j/search_subjects?type=movie&tag="
                + URLEncoder.encode(tag, "UTF-8")
                + "&sort=recommend&page_limit=" + limit + "&page_start=" + start;

        String body = get(url);
        List<DoubanItem> out = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return out;

        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("subjects");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            DoubanItem it = new DoubanItem();
            it.id = o.optString("id", "");
            it.title = o.optString("title", "");
            it.rate = o.optString("rate", "");
            it.cover = o.optString("cover", "");
            it.url = o.optString("url", "");
            it.from = "douban";
            if (!TextUtils.isEmpty(it.title)) out.add(it);
        }
        return out;
    }

    /* ==================== 详情 ==================== */

    /**
     * 取单条详情（简介 / 年份等）。失败返回传入的 item 本身，不抛异常。
     */
    public static DoubanItem detail(DoubanItem item) {
        if (item == null || TextUtils.isEmpty(item.url)) return item;
        try {
            String html = get(item.url);
            if (TextUtils.isEmpty(html)) return item;
            item.year = match(html, "property=\"v:initialReleaseDate\" content=\"(\\d{4})");
            if (TextUtils.isEmpty(item.year)) {
                item.year = match(html, "\\((\\d{4})\\)");
            }
            String intro = match(html, "property=\"v:summary\"[^>]*>([\\s\\S]*?)</span>");
            if (!TextUtils.isEmpty(intro)) {
                item.intro = intro.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
            }
            if (TextUtils.isEmpty(item.rate)) {
                item.rate = match(html, "property=\"v:average\">([\\d.]+)");
            }
        } catch (Throwable ignored) {
        }
        return item;
    }

    /* ==================== 工具 ==================== */

    /**
     * 封面代理：豆瓣图片有 Referer 防盗链，直连在电视上会 403。
     * 这里统一走 images.weserv.nl（支持 https/http 源，带缓存）。
     */
    public static String proxyImage(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String u = raw.trim();
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.startsWith("http")) return u;
        try {
            String stripped = u.replaceFirst("^https?://", "");
            return "https://images.weserv.nl/?url=" + URLEncoder.encode(stripped, "UTF-8");
        } catch (Throwable t) {
            return u;
        }
    }

    private static String get(String url) {
        return DoubanHttp.get(url, 2 * 1024 * 1024, UA, REFERER);
    }

    private static String match(String src, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(src);
            if (m.find()) return m.group(1).trim();
        } catch (Throwable ignored) {
        }
        return "";
    }
}
