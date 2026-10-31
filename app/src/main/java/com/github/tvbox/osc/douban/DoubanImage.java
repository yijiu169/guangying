package com.github.tvbox.osc.douban;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

/**
 * 豆瓣图片加载辅助。
 *
 * <p>此前封面统一走 {@code images.weserv.nl} 海外代理，国内电视盒子访问极不稳定
 * （慢/超时/被墙），表现为「海报墙全是占位图」。豆瓣图片本身在国内 CDN（doubanio.com）
 * 可直连，只是有 Referer 防盗链。这里改为：<b>直连豆瓣 CDN + 携带 Referer/UA</b>，
 * 由 Glide 通过 {@link com.bumptech.glide.load.model.Headers} 注入请求头，绕开防盗链。
 */
public final class DoubanImage {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Safari/537.36";

    /** 豆瓣图片防盗链需要的 Referer */
    static final String REFERER = "https://movie.douban.com/";

    private static final LazyHeaders HEADERS = new LazyHeaders.Builder()
            .addHeader("Referer", REFERER)
            .addHeader("User-Agent", UA)
            .build();

    private DoubanImage() {
    }

    /** 把豆瓣返回的原始封面地址规范化成可直连的 https 地址 */
    public static String direct(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.startsWith("http")) return "";
        // 老的 http 图统一走 https，避免明文被中间网络拦截
        if (u.startsWith("http://")) u = "https://" + u.substring(7);
        return u;
    }

    /** 构造带防盗链头的 Glide 请求（调用前请先确保 direct(raw) 非空） */
    public static GlideUrl req(String raw) {
        return new GlideUrl(direct(raw), HEADERS);
    }
}
