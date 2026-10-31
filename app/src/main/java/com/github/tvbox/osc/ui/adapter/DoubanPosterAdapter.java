package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.douban.DoubanItem;

/**
 * 豆瓣海报墙适配器。
 * 焦点态：整卡放大 1.08 + 上浮，同排其它卡片压暗（与原型一致）。
 */
public class DoubanPosterAdapter extends BaseQuickAdapter<DoubanItem, BaseViewHolder> {

    public DoubanPosterAdapter() {
        super(R.layout.item_douban_poster);
    }

    @Override
    protected void convert(BaseViewHolder helper, DoubanItem item) {
        View root = helper.itemView;
        ImageView cover = helper.getView(R.id.iv_poster);
        TextView title = helper.getView(R.id.tv_title);
        TextView rate = helper.getView(R.id.tv_rate);
        TextView year = helper.getView(R.id.tv_year);

        title.setText(item.title);
        rate.setText(item.rate == null || item.rate.isEmpty() ? "—" : item.rate);
        year.setText(item.year == null || item.year.isEmpty() ? "" : item.year);

        Glide.with(root.getContext())
                .load(com.github.tvbox.osc.douban.DoubanImage.req(item.cover))
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .placeholder(R.drawable.bg_poster_placeholder)
                .error(R.drawable.bg_poster_placeholder)
                .centerCrop()
                .into(cover);

        // 电视端焦点：真实焦点在 itemView 上（TvRecyclerView 管理）
        root.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.08f : 1.0f;
            float t = hasFocus ? -6f : 0f;
            v.animate().scaleX(s).scaleY(s).translationY(t).setDuration(180).start();
            v.setSelected(hasFocus);
        });
        root.setSelected(root.hasFocus());
    }
}
