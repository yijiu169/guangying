package com.github.tvbox.osc.douban;

import java.io.Serializable;

/**
 * 豆瓣条目（元数据层，与播放源完全解耦）。
 * 切播放源不会影响这里的任何字段。
 */
public class DoubanItem implements Serializable {

    /** 豆瓣 subject id */
    public String id = "";

    public String title = "";

    /** 评分，如 8.7；暂无评分时为空 */
    public String rate = "";

    /** 年份 */
    public String year = "";

    /** 封面原始地址 */
    public String cover = "";

    /** 详情页地址 */
    public String url = "";

    /** 类型 / 地区等副标题 */
    public String subtitle = "";

    /** 简介 */
    public String intro = "";

    /** 数据来源：douban / tmdb */
    public String from = "douban";

    /** 可直接展示的封面地址（已做防盗链处理） */
    public String coverUrl() {
        return DoubanApi.proxyImage(cover);
    }

    @Override
    public String toString() {
        return title + "(" + year + ") " + rate;
    }
}
